/*
 * Copyright 2018 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.gradle.internal.execution.steps;

import com.google.common.collect.ImmutableSortedMap;
import org.gradle.internal.change.ChangeDetectorVisitor;
import org.gradle.internal.execution.CurrentSnapshotResult;
import org.gradle.internal.execution.IncrementalContext;
import org.gradle.internal.execution.Step;
import org.gradle.internal.execution.UnitOfWork;
import org.gradle.internal.execution.history.AfterPreviousExecutionState;
import org.gradle.internal.execution.history.OutputFilesRepository;
import org.gradle.internal.execution.history.changes.OutputFileChanges;
import org.gradle.internal.fingerprint.CurrentFileCollectionFingerprint;
import org.gradle.internal.fingerprint.FileCollectionFingerprint;

import java.util.Optional;

public class StoreSnapshotsStep<C extends IncrementalContext> implements Step<C, CurrentSnapshotResult> {
    private final OutputFilesRepository outputFilesRepository;
    private final Step<? super C, ? extends CurrentSnapshotResult> delegate;

    public StoreSnapshotsStep(
        OutputFilesRepository outputFilesRepository,
        Step<? super C, ? extends CurrentSnapshotResult> delegate
    ) {
        this.outputFilesRepository = outputFilesRepository;
        this.delegate = delegate;
    }

    @Override
    // TODO Return a simple Result (that includes the origin metadata) here
    public CurrentSnapshotResult execute(C context) {
        CurrentSnapshotResult result = delegate.execute(context);
        ImmutableSortedMap<String, CurrentFileCollectionFingerprint> finalOutputs = result.getFinalOutputs();
        context.getBeforeExecutionState().ifPresent(beforeExecutionState -> {
            boolean successful = result.getOutcome().isSuccessful();
            Optional<AfterPreviousExecutionState> afterPreviousExecutionState = context.getAfterPreviousExecutionState();
            // Only persist history if there was no failure, or some output files have been changed
            UnitOfWork work = context.getWork();
            if (successful
                || !afterPreviousExecutionState.isPresent()
                || hasAnyOutputFileChanges(afterPreviousExecutionState.get().getOutputFileProperties(), finalOutputs)) {
                work.getExecutionHistoryStore().store(
                    work.getIdentity(),
                    result.getOriginMetadata(),
                    beforeExecutionState.getImplementation(),
                    beforeExecutionState.getAdditionalImplementations(),
                    beforeExecutionState.getInputProperties(),
                    beforeExecutionState.getInputFileProperties(),
                    finalOutputs,
                    successful
                );
            }
        });
        outputFilesRepository.recordOutputs(finalOutputs.values());

        return result;
    }

    private static boolean hasAnyOutputFileChanges(ImmutableSortedMap<String, FileCollectionFingerprint> previous, ImmutableSortedMap<String, CurrentFileCollectionFingerprint> current) {
        if (!previous.keySet().equals(current.keySet())) {
            return true;
        }
        ChangeDetectorVisitor visitor = new ChangeDetectorVisitor();
        OutputFileChanges changes = new OutputFileChanges(previous, current, true);
        changes.accept(visitor);
        return visitor.hasAnyChanges();
    }
}