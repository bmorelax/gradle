/*
 * Copyright 2013 the original author or authors.
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

package org.gradle.api.internal.tasks.compile.incremental;

import org.gradle.api.internal.changedetection.rules.FileChange;
import org.gradle.api.internal.file.FileOperations;
import org.gradle.api.internal.tasks.compile.incremental.classpath.ClasspathEntryChangeProcessor;
import org.gradle.api.internal.tasks.compile.incremental.classpath.ClasspathSnapshot;
import org.gradle.api.internal.tasks.compile.incremental.classpath.ClasspathEntrySnapshot;
import org.gradle.api.internal.tasks.compile.incremental.classpath.PreviousCompilation;
import org.gradle.api.internal.tasks.compile.incremental.recomp.RecompilationSpec;
import org.gradle.internal.file.FileType;
import org.gradle.internal.util.Alignment;

import java.io.File;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class RecompilationSpecProvider {

    private final SourceToNameConverter sourceToNameConverter;
    private final FileOperations fileOperations;

    RecompilationSpecProvider(SourceToNameConverter sourceToNameConverter, FileOperations fileOperations) {
        this.sourceToNameConverter = sourceToNameConverter;
        this.fileOperations = fileOperations;
    }

    public RecompilationSpec provideRecompilationSpec(CurrentCompilation current, PreviousCompilation previous) {
        RecompilationSpec spec = new RecompilationSpec();
        processJarClasspathChanges(current, previous, spec);
        processOtherChanges(current, previous, spec);
        spec.getClassesToProcess().addAll(previous.getAggregatedTypes().getDependentClasses());
        return spec;
    }

    private void processJarClasspathChanges(CurrentCompilation current, PreviousCompilation previous, RecompilationSpec spec) {
        ClasspathEntryChangeProcessor classpathEntryChangeProcessor = new ClasspathEntryChangeProcessor(fileOperations, current.getClasspathSnapshot(), previous);
        Map<File, ClasspathEntrySnapshot> previousCompilationJarSnapshots = previous.getSnapshots();
        ClasspathSnapshot currentJarSnapshots = current.getClasspathSnapshot();

        Set<File> previousCompilationJars = previousCompilationJarSnapshots.keySet();
        Set<File> currentCompilationJars = currentJarSnapshots.getEntries();
        List<Alignment<File>> alignment = Alignment.align(currentCompilationJars.toArray(new File[0]), previousCompilationJars.toArray(new File[0]));
        for (Alignment<File> fileAlignment : alignment) {
            switch (fileAlignment.getKind()) {
                case added:
                    classpathEntryChangeProcessor.processChange(FileChange.added(fileAlignment.getCurrentValue().getAbsolutePath(), "jar", FileType.RegularFile), spec);
                    break;
                case removed:
                    classpathEntryChangeProcessor.processChange(FileChange.removed(fileAlignment.getPreviousValue().getAbsolutePath(), "jar", FileType.RegularFile), spec);
                    break;
                case transformed:
                    // If we detect a transformation in the classpath, we need to recompile, because we could typically be facing the case where
                    // 2 jars are reversed in the order of classpath elements, and one class that was shadowing the other is now visible
                    spec.setFullRebuildCause("Classpath has been changed", null);
                    return;
                case identical:
                    File key = fileAlignment.getPreviousValue();
                    ClasspathEntrySnapshot previousSnapshot = previousCompilationJarSnapshots.get(key);
                    ClasspathEntrySnapshot snapshot = currentJarSnapshots.getSnapshot(key);
                    if (!snapshot.getHash().equals(previousSnapshot.getHash())) {
                        classpathEntryChangeProcessor.processChange(FileChange.modified(key.getAbsolutePath(), "jar", FileType.RegularFile, FileType.RegularFile), spec);
                    }
                    break;
            }
        }
    }

    private void processOtherChanges(CurrentCompilation current, PreviousCompilation previous, RecompilationSpec spec) {
        JavaChangeProcessor javaChangeProcessor = new JavaChangeProcessor(previous, sourceToNameConverter);
        ClassChangeProcessor classChangeProcessor = new ClassChangeProcessor(previous);
        AnnotationProcessorChangeProcessor annotationProcessorChangeProcessor = new AnnotationProcessorChangeProcessor(current, previous);
        InputChangeAction action = new InputChangeAction(spec, javaChangeProcessor, classChangeProcessor, annotationProcessorChangeProcessor);
        current.visitChanges(action);
    }


}
