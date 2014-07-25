// Copyright 2014 Google Inc. All rights reserved.
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//    http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
package com.google.devtools.build.skyframe;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Objects;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Iterables;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.devtools.build.lib.util.GroupedList;
import com.google.devtools.build.lib.util.GroupedList.GroupedListHelper;
import com.google.devtools.build.lib.util.Pair;

import java.util.Collection;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

/**
 * A value in the graph. All operations on this class are thread-safe. Care was taken to provide
 * certain compound operations to avoid certain check-then-act races. That means this class is
 * somewhat closely tied to the exact Evaluator implementation.
 *
 * <p>Consider the example with two threads working on two values, where one depends on the other,
 * say b depends on a. If a completes first, it's done. If it completes second, it needs to signal
 * b, and potentially re-schedule it. If b completes first, it must exit, because it will be
 * signaled (and re-scheduled) by a. If it completes second, it must signal (and re-schedule)
 * itself. However, if the Evaluator supported re-entrancy for a value, then this wouldn't have to
 * be so strict, because duplicate scheduling would be less problematic.
 *
 * <p>The transient state of a {@code ValueEntry} is kept in a {@link BuildingState} object. Many of
 * the methods of {@code ValueEntry} are just wrappers around the corresponding
 * {@link BuildingState} methods.
 *
 * <p>This class is non-final only for testing purposes.
 */
class ValueEntry {
  /**
   * Return code for {@link #addReverseDepAndCheckIfDone(SkyKey)}.
   */
  enum DependencyState {
    /** The value is done. */
    DONE,
    /**
     * The value was just created and needs to be scheduled for its first evaluation pass. The value
     * evaluator is responsible for signaling the reverse dependency value.
     */
    NEEDS_SCHEDULING,
    /**
     * The value was already created, but isn't done yet. The value evaluator is responsible for
     * signaling the reverse dependency value.
     */
    ADDED_DEP;
  }

  /** Actual data stored in this entry when it is done. */
  private SkyValue value = null;
  /**
   * The last version of the graph at which this value entry was changed. In {@link #setValue} it
   * may be determined that the data being written to the graph at a given version is the same as
   * the already-stored data. In that case, the version will remain the same. The version can be
   * thought of as the latest timestamp at which this entry was changed.
   */
  private long version = -1L;
  /**
   * This object represents a {@link GroupedList}<SkyKey> in a memory-efficient way. It stores the
   * direct dependencies of this value, in groups if the value builder requested them that way.
   */
  private Object directDeps = null;

  /**
   * This list stores the reverse dependencies of this value that have been declared so far.
   *
   * <p>In case of a single object we store the object unwrapped, without the list, for
   * memory-efficiency.
   */
  @VisibleForTesting
  protected Object reverseDeps = ImmutableList.of();
  /**
   * We take advantage of memory alignment to avoid doing a nasty {@code instanceof} for knowing
   * if {@code reverseDeps} is a single object or a list.
   */
  protected boolean reverseDepIsSingleObject = false;
  /**
   * During the invalidation we keep the reverse deps to be removed in this list instead of directly
   * removing them from {@code reverseDeps}. That is because removals from reverseDeps are O(N).
   * Originally reverseDeps was a HashSet, but because of memory consumption we switched to a list.
   *
   * <p>This requires that any usage of reverseDeps (contains, add, the list of reverse deps) call
   * {@code consolidateReverseDepsRemovals} first. While this operation is not free, it can be done
   * more effectively than trying to remove each dirty reverse dependency individually (O(N) each
   * time).
   */
  private List<SkyKey> reverseDepsToRemove = null;

  /**
   * The transient state of this entry, after it has been created but before it is done. It allows
   * us to keep the current state of the entry across invalidation and successive evaluations.
   */
  @VisibleForTesting
  protected BuildingState buildingState = new BuildingState();

  ValueEntry() {
  }

  /** Returns whether the entry has been built and is finished evaluating. */
  synchronized boolean isDone() {
    return buildingState == null;
  }

  /**
   * Returns the value stored in this entry. This method may only be called after the evaluation of
   * this value is complete, i.e., after {@link #setValue} has been called.
   */
  synchronized SkyValue getValue() {
    Preconditions.checkState(isDone(), "no value until done. ValueEntry: %s", this);
    return ValueWithMetadata.justValue(value);
  }

  /**
   * Returns the {@link SkyValue} for this entry and the metadata associated with it (Like events
   * and errors). This method may only be called after the evaluation of this value is complete,
   * i.e., after {@link #setValue} has been called.
   */
  synchronized ValueWithMetadata getValueWithMetadata() {
    Preconditions.checkState(isDone(), "no value until done", this);
    return ValueWithMetadata.wrapWithMetadata(value);
  }

  /**
   * Returns the Value value, even if dirty or changed. Returns null otherwise.
   */
  public synchronized SkyValue toValueValue() {
    if (isDone()) {
      return getErrorInfo() == null ? getValue() : null;
    } else if (isChanged() || isDirty()) {
      return (buildingState.getLastBuildValue() == null)
              ? null
          : ValueWithMetadata.justValue(buildingState.getLastBuildValue());
    }
    throw new AssertionError("Value in bad state: " + this);
  }

  /**
   * Returns an immutable iterable of the direct deps of this value. This method may only be called
   * after the evaluation of this value is complete, i.e., after {@link #setValue} has been called.
   *
   * <p>This method is not very efficient, but is only be called in limited circumstances --
   * when the value is about to be deleted, or when the value is expected to have no direct deps (in
   * which case the overhead is not so bad). It should not be called repeatedly for the same value,
   * since each call takes time proportional to the number of direct deps of the value.
   */
  synchronized Iterable<SkyKey> getDirectDeps() {
    Preconditions.checkState(isDone(), "no deps until done. ValueEntry: %s", this);
    return GroupedList.<SkyKey>create(directDeps).toSet();
  }

  /**
   * Returns the error, if any, associated to this value. This method may only be called after
   * the evaluation of this value is complete, i.e., after {@link #setValue} has been called.
   */
  @Nullable
  synchronized ErrorInfo getErrorInfo() {
    Preconditions.checkState(isDone(), "no errors until done. ValueEntry: %s", this);
    return ValueWithMetadata.getMaybeErrorInfo(value);
  }

  private synchronized Set<SkyKey> setStateFinishedAndReturnReverseDeps() {
    // Get reverse deps that need to be signaled.
    ImmutableSet<SkyKey> reverseDepsToSignal = buildingState.getReverseDepsToSignal();
    consolidateReverseDepsRemovals();
    addReverseDeps(reverseDepsToSignal);
    this.directDeps = buildingState.getFinishedDirectDeps().compress();

    // Set state of entry to done.
    buildingState = null;

    return reverseDepsToSignal;
  }

  /**
   * Returns the set of reverse deps that have been declared so far this build. Only for use in
   * debugging and when bubbling errors up in the --nokeep_going case, where we need to know what
   * parents this entry has.
   */
  synchronized Set<SkyKey> getInProgressReverseDeps() {
    Preconditions.checkState(!isDone(), this);
    return buildingState.getReverseDepsToSignal();
  }

  /**
   * Transitions the value from the EVALUATING to the DONE state and simultaneously sets it to the
   * given value and error state. It then returns the set of reverse dependencies that need to be
   * signaled.
   *
   * <p>This is an atomic operation to avoid a race where two threads work on two values, where one
   * value depends on another (b depends on a). When a finishes, it signals <b>exactly</b> the set
   * of reverse dependencies that are registered at the time of the {@code setValue} call. If b
   * comes in before a, it is signaled (and re-scheduled) by a, otherwise it needs to do that
   * itself.
   *
   * <p>{@code version} indicates the graph version at which this value is being written. If the
   * entry determines that the new value is equal to the previous value, the entry will keep its
   * current version. Callers can query that version to see if the value considers its value to have
   * changed.
   */
  synchronized Set<SkyKey> setValue(SkyValue value, long version) {
    Preconditions.checkState(isReady(), "%s %s", this, value);
    Preconditions.checkState(version >= 0L, "%s %s %s", this, version, value);
    Preconditions.checkState(this.version <= version, "%s %s %s", this, version, value);

    if (isDirty() && buildingState.unchangedFromLastBuild(value)) {
      // If the value is the same as before, just use the old value. Note that we don't use the new
      // value, because preserving == equality is even better than .equals() equality.
      this.value = buildingState.getLastBuildValue();
    } else {
      // If this is a new value, or it has changed since the last build, set the version to the
      // current graph version.
      this.version = version;
      this.value = value;
    }

    return setStateFinishedAndReturnReverseDeps();
  }

  /**
   * Queries if the value is done and adds the given key as a reverse dependency. The return code
   * indicates whether a) the value is done, b) the reverse dependency is the first one, so the
   * value needs to be scheduled, or c) the reverse dependency was added, and the value does not
   * need to be scheduled.
   *
   * <p>This method <b>must</b> be called before any processing of the entry. This encourages
   * callers to check that the entry is ready to be processed.
   *
   * <p>Adding the dependency and checking if the value needs to be scheduled is an atomic operation
   * to avoid a race where two threads work on two values, where one depends on the other (b depends
   * on a). In that case, we need to ensure that b is re-scheduled exactly once when a is done.
   * However, a may complete first, in which case b has to re-schedule itself. Also see {@link
   * #setValue}.
   *
   * <p>If the parameter is {@code null}, then no reverse dependency is added, but we still check
   * if the value needs to be scheduled.
   */
  synchronized DependencyState addReverseDepAndCheckIfDone(SkyKey reverseDep) {
    if (reverseDep != null) {
      consolidateReverseDepsRemovals();
      maybeCheckReverseDepNotPresent(reverseDep);
      if (isDone()) {
        addReverseDeps(ImmutableList.of(reverseDep));
      } else {
        // Parent should never register itself twice in the same build.
        Preconditions.checkState(buildingState.addReverseDepToSignal(reverseDep),
            "%s %s", reverseDep, this);
      }
    }
    if (isDone()) {
      return DependencyState.DONE;
    }
    return buildingState.startEvaluating() ? DependencyState.NEEDS_SCHEDULING
                                           : DependencyState.ADDED_DEP;
  }

  /**
   * We check that the reverse dependency is not already present. We only do that if reverseDeps
   * is small, so that it does not impact performance.
   */
  private void maybeCheckReverseDepNotPresent(SkyKey reverseDep) {
    if (reverseDepIsSingleObject) {
      Preconditions.checkState(reverseDep.equals(reverseDep), "Reverse dep %s already present",
          reverseDep);
      return;
    }
    @SuppressWarnings("unchecked")
    List<SkyKey> asList = (List<SkyKey>) reverseDeps;
    if (asList.size() < 10) {
      Preconditions.checkState(!asList.contains(reverseDep), "Reverse dep %s already present"
          + " in %s", reverseDep, asList);
    }
  }

  /**
   * Removes a reverse dependency.
   */
  synchronized void removeReverseDep(SkyKey reverseDep) {
    removeReverseDepInternal(reverseDep);
    if (!isDone()) {
      // This is currently unnecessary -- the only time we remove a reverse dep that was added this
      // build is during the clean following a build failure. In that case, this value that is not
      // done will be deleted soon, so clearing the reverse dep is not required.
      buildingState.removeReverseDepToSignal(reverseDep);
    }
  }

  /**
   * We use a memory-efficient trick to keep reverseDeps memory usage low. Edges in Blaze are
   * dominant over the number of values.
   *
   * <p>Most of the values have zero or one reverse dep. That is why we use immutable versions of
   * the lists for those cases. In case of the size being > 1 we switch to an ArrayList. That is
   * because we also have a decent number of values for which the reverseDeps are huge (for
   * example almost everything depends on BuildInfo value).
   */
  // TODO(bazel-team): One potential new candidate for saving memory would be to keep a direct
  // reference for size = 1, instead of wrapping it in a list. But for now I want to keep it simple
  @SuppressWarnings("unchecked")
  private void addReverseDeps(Collection<SkyKey> newReverseDeps) {
    if (newReverseDeps.isEmpty()) {
      return;
    }
    int reverseDepsSize = reverseDepIsSingleObject ? 1 : ((List<SkyKey>) reverseDeps).size();
    int newSize = reverseDepsSize + newReverseDeps.size();
    if (newSize == 1) {
      overwriteReverseDepsWithObject(Iterables.getOnlyElement(newReverseDeps));
    } else if (reverseDepsSize == 0) {
      overwriteReverseDepsList(Lists.newArrayList(newReverseDeps));
    } else if (reverseDepsSize == 1) {
      List<SkyKey> newList = Lists.newArrayListWithExpectedSize(newSize);
      newList.add((SkyKey) reverseDeps);
      newList.addAll(newReverseDeps);
      overwriteReverseDepsList(newList);
    } else {
      ((List<SkyKey>) reverseDeps).addAll(newReverseDeps);
    }
  }

  private void overwriteReverseDepsWithObject(SkyKey newObject) {
    reverseDeps = newObject;
    reverseDepIsSingleObject = true;
  }

  private void overwriteReverseDepsList(List<SkyKey> list) {
    reverseDeps = list;
    reverseDepIsSingleObject = false;
  }

  private void consolidateReverseDepsRemovals() {
    if (reverseDepsToRemove == null) {
      return;
    }
    // Should not happen, as we only create reverseDepsToRemove in case we have at least one
    // reverse dep to remove.
    Preconditions.checkState(reverseDepIsSingleObject || (!((List<?>) reverseDeps).isEmpty()),
          "Could not remove %s elements from %s.\nReverse deps to remove: %s",
          reverseDepsToRemove.size(),
          reverseDeps, reverseDepsToRemove);

    // It might be the immutable single list if we failed to remove the reverse dep.
    if (reverseDepIsSingleObject) {
      Preconditions.checkState(reverseDepsToRemove.size() == 1
              && reverseDepsToRemove.contains(reverseDeps),
          "Could not remove %s elements from %s.\nReverse deps to remove: %s",
          reverseDepsToRemove.size(),
          reverseDeps, reverseDepsToRemove
      );
      overwriteReverseDepsList(ImmutableList.<SkyKey>of());
      return;
    }

    Set<SkyKey> toRemove = Sets.newHashSet(reverseDepsToRemove);
    int expectedRemovals = toRemove.size();
    Preconditions.checkState(expectedRemovals == reverseDepsToRemove.size(),
        "A reverse dependency tried to remove itself twice: %s", reverseDepsToRemove);

    @SuppressWarnings("unchecked")
    List<SkyKey> reverseDepsAsList = (List<SkyKey>) reverseDeps;
    List<SkyKey> newReverseDeps = Lists
        .newArrayListWithExpectedSize(reverseDepsAsList.size() - expectedRemovals);

    for (SkyKey reverseDep : reverseDepsAsList) {
      if (!toRemove.contains(reverseDep)) {
        newReverseDeps.add(reverseDep);
      }
    }
    Preconditions.checkState(newReverseDeps.size() == reverseDepsAsList.size() - expectedRemovals,
        "Could not remove some elements from %s.\nReverse deps to remove: %s", reverseDeps,
        toRemove);

    if (newReverseDeps.isEmpty()) {
      overwriteReverseDepsList(ImmutableList.<SkyKey>of());
    } else if (newReverseDeps.size() == 1) {
      overwriteReverseDepsWithObject(newReverseDeps.get(0));
    } else {
      overwriteReverseDepsList(newReverseDeps);
    }
    reverseDepsToRemove = null;
  }

  /**
   * See {@code addReverseDeps} method.
   */
  private void removeReverseDepInternal(SkyKey reverseDep) {
    if (reverseDepIsSingleObject) {
      // This removal is cheap so let's do it and not keep it in reverseDepsToRemove.
      // contains should only return false in case of catastrophe.
      if (reverseDeps.equals(reverseDep)) {
        overwriteReverseDepsList(ImmutableList.<SkyKey>of());
      }
      return;
    }
    @SuppressWarnings("unchecked")
    List<SkyKey> reverseDepsAsList = (List<SkyKey>) reverseDeps;
    if (reverseDepsAsList.isEmpty()) {
      return;
    }
    if (reverseDepsToRemove == null) {
      reverseDepsToRemove = Lists.newArrayListWithExpectedSize(1);
    }
    reverseDepsToRemove.add(reverseDep);
  }

  /**
   * Returns a copy of the set of reverse dependencies. Note that this introduces a potential
   * check-then-act race; {@link #removeReverseDep} may fail for a key that is returned here.
   */
  synchronized Iterable<SkyKey> getReverseDeps() {
    Preconditions.checkState(isDone() || buildingState.getReverseDepsToSignal().isEmpty(),
        "Reverse deps should only be queried before the build has begun "
        + "or after the value is done %s", this);
    consolidateReverseDepsRemovals();

    // TODO(bazel-team): Unfortunately, we need to make a copy here right now to be on the safe side
    // wrt. thread-safety. The parents of a value get modified when any of the parents is deleted,
    // and we can't handle that right now.
    if (reverseDepIsSingleObject) {
      return ImmutableSet.of((SkyKey) reverseDeps);
    } else {
      ImmutableSet<SkyKey> set = ImmutableSet.copyOf((Iterable<SkyKey>) reverseDeps);
      Preconditions.checkState(set.size() == ((List<?>) reverseDeps).size(),
          "Duplicate reverse deps present in %s: %s", this, reverseDeps);
      return set;
    }
  }

  /**
   * Tell this value that one of its dependencies is now done. Callers must check the return value,
   * and if true, they must re-schedule this value for evaluation. Equivalent to
   * {@code #signalDep(Long.MAX_VALUE)}. Since this entry's version is less than
   * {@link Long#MAX_VALUE}, informing this entry that a child of it has version
   * {@link Long#MAX_VALUE} will force it to re-evaluate.
   */
  synchronized boolean signalDep() {
    return signalDep(/*childVersion=*/Long.MAX_VALUE);
  }

  /**
   * Tell this entry that one of its dependencies is now done. Callers must check the return value,
   * and if true, they must re-schedule this value for evaluation.
   *
   * @param childVersion If this entry {@link #isDirty()} and {@code childVersion} is greater than
   * {@link #getVersion()}, then this entry records that one of its children has changed since it
   * was last evaluated (namely, it was last evaluated at version {@link #getVersion()} and the
   * child was last evaluated at {@code childVersion}. Thus, the next call to
   * {@link #getDirtyState()} will return {@link BuildingState.DirtyState#REBUILDING}.
   */
  synchronized boolean signalDep(long childVersion) {
    Preconditions.checkState(!isDone(), "Value must not be done in signalDep %s", this);
    return buildingState.signalDep(/*childChanged=*/childVersion > getVersion());
  }

  /**
   * Returns true if the entry is marked dirty, meaning that at least one of its transitive
   * dependencies is marked changed.
   */
  synchronized boolean isDirty() {
    return !isDone() && buildingState.isDirty();
  }

  /**
   * Returns true if the entry is marked changed, meaning that it must be re-evaluated even if its
   * dependencies' values have not changed.
   */
  synchronized boolean isChanged() {
    return !isDone() && buildingState.isChanged();
  }

  /**
   * Marks this value dirty, or changed if {@code isChanged} is true. The value is put in the
   * just-created state. It will be re-evaluated if necessary during the evaluation phase,
   * but if it has not changed, it will not force a re-evaluation of its parents.
   *
   * @return The direct deps and value of this entry, or null if the entry has already been marked
   * dirty. In the latter case, the caller should abort its handling of this value, since another
   * thread is already dealing with it.
   */
  @Nullable
  synchronized Pair<? extends Iterable<SkyKey>, ? extends SkyValue> markDirty(boolean isChanged) {
    if (isDone()) {
      GroupedList<SkyKey> lastDirectDeps = GroupedList.create(directDeps);
      buildingState = BuildingState.newDirtyState(isChanged, lastDirectDeps, value);
      Pair<? extends Iterable<SkyKey>, ? extends SkyValue> result =
          Pair.of(lastDirectDeps.toSet(), value);
      value = null;
      directDeps = null;
      return result;
    }
    // The caller may be simultaneously trying to mark this value dirty and changed, and the dirty
    // thread may have lost the race, but it is the caller's responsibility not to try to mark
    // this value changed twice. The end result of racing markers must be a changed value, since one
    // of the markers is trying to mark the value changed.
    Preconditions.checkState(isChanged != isChanged(),
        "Cannot mark value dirty twice or changed twice: %s", this);
    Preconditions.checkState(value == null, "Value should have been reset already %s", this);
    Preconditions.checkState(directDeps == null, "direct deps not already reset %s", this);
    if (isChanged) {
      // If the changed marker lost the race, we just need to mark changed in this method -- all
      // other work was done by the dirty marker.
      buildingState.markChanged();
    }
    return null;
  }

  /**
   * Marks this entry as up-to-date at this version.
   *
   * @return {@link Set} of reverse dependencies to signal that this value is done.
   */
  synchronized Set<SkyKey> markClean() {
    this.value = buildingState.getLastBuildValue();
    // This checks both the value and the direct deps, but since we're passing in the same value,
    // the value check should be trivial.
    Preconditions.checkState(buildingState.unchangedFromLastBuild(this.value),
        "Direct deps must be the same as those found last build for value to be marked clean",
        this);
    Preconditions.checkState(isDirty(), this);
    Preconditions.checkState(!buildingState.isChanged(), "shouldn't be changed:", this);
    return setStateFinishedAndReturnReverseDeps();
  }

  /**
   * Forces this value to be rebuilt, even if none of its dependencies are known to have changed.
   * Used when an external caller has reason to believe that re-evaluating may yield a new result.
   * This method should not be used if one of the normal deps of this value has changed -- the usual
   * change-pruning process should work in that case.
   */
  synchronized void forceRebuild() {
    buildingState.forceChanged();
  }

  /**
   * Gets the current version of this entry.
   */
  synchronized long getVersion() {
    return version;
  }

  /**
   * Gets the current state of checking this dirty entry to see if it must be re-evaluated. Must be
   * called each time evaluation of a dirty entry starts to find the proper action to perform next,
   * as enumerated by {@link BuildingState.DirtyState}.
   *
   * @see BuildingState#getDirtyState()
   */
  synchronized BuildingState.DirtyState getDirtyState() {
    return buildingState.getDirtyState();
  }

  /**
   * Should only be called if the entry is dirty. During the examination to see if the entry must be
   * re-evaluated, this method returns the next group of children to be checked. Callers should
   * have already called {@link #getDirtyState} and received a return value of
   * {@link BuildingState.DirtyState#CHECK_DEPENDENCIES} before calling this method -- any other
   * return value from {@link #getDirtyState} means that this method must not be called, since
   * whether or not the value needs to be rebuilt is already known.
   *
   * <p>Deps are returned in groups. The deps in each group were requested in parallel by the
   * builder last build, meaning independently of the values of any other deps in this group
   * (although possibly depending on deps in earlier groups). Thus the caller may check all the deps
   * in this group in parallel, since the deps in all previous groups are verified unchanged. See
   * {@link SkyFunction.Environment#getValues} for more on dependency groups.
   *
   * <p>The caller should register these as deps of this entry using {@link #addTemporaryDirectDeps}
   * before checking them.
   *
   * @see BuildingState#getNextDirtyDirectDeps()
   */
  synchronized Collection<SkyKey> getNextDirtyDirectDeps() {
    return buildingState.getNextDirtyDirectDeps();
  }

  /**
   * Returns the set of direct dependencies. This may only be called while the value is being
   * evaluated, that is, before {@link #setValue} and after {@link #markDirty}.
   */
  synchronized Set<SkyKey> getTemporaryDirectDeps() {
    Preconditions.checkState(!isDone(), "temporary shouldn't be done: %s", this);
    return buildingState.getDirectDepsForBuild();
  }

  synchronized boolean noDepsLastBuild() {
    return buildingState.noDepsLastBuild();
  }

  /**
   * Remove dep from direct deps. This should only be called if this entry is about to be
   * committed as a cycle value, but some of its children were not checked for cycles, either
   * because the cycle was discovered before some children were checked; some children didn't have a
   * chance to finish before the evaluator aborted; or too many cycles were found when it came time
   * to check the children.
   */
  synchronized void removeUnfinishedDeps(Set<SkyKey> unfinishedDeps) {
    buildingState.removeDirectDeps(unfinishedDeps);
  }

  synchronized void addTemporaryDirectDeps(GroupedListHelper<SkyKey> helper) {
    Preconditions.checkState(!isDone(), "add temp shouldn't be done: %s %s", helper, this);
    buildingState.addDirectDeps(helper);
  }

  /**
   * Add a direct dependency to this value. This may only be called while the value is being
   * evaluated, that is, before {@link #setValue} and after {@link #markDirty}. Note that the
   * temporary deps implicitly become the direct deps of the value (see {@link #getDirectDeps} when
   * {@link #setValue} is called. It is an illegal operation to add the same dep twice in the same
   * build, and this method will fail-fast if that is attempted.
   *
   * <p>The main purpose of this method is to keep state between evaluation attempts.
   *
   * @param dep child this value depends on
   * @param continueGroup true if the set of deps requested after this one would be requested
   * regardless of the value of this value, false, otherwise -- if the set of deps requested after
   * this one depends on the value of this value. Used for change pruning: if continueGroup is
   * false, this dep will be checked to see if it has changed before any later deps are checked.
   */

  /**
   * Returns true if the value is ready to be evaluated, i.e., it has been signaled exactly as many
   * times as it has temporary dependencies. This may only be called while the value is being
   * evaluated, that is, before {@link #setValue} and after {@link #markDirty}.
   */
  synchronized boolean isReady() {
    Preconditions.checkState(!isDone(), "can't be ready if done: %s", this);
    return buildingState.isReady();
  }

  @Override
  @SuppressWarnings("deprecation")
  public String toString() {
    return Objects.toStringHelper(this)  // MoreObjects is not in Guava
        .add("value", value)
        .add("version", version)
        .add("directDeps", directDeps == null ? null : GroupedList.create(directDeps))
        .add("reverseDeps", reverseDeps)
        .add("buildingState", buildingState).toString();
  }
}