/*
 * Copyright (C) 2025 DANS - Data Archiving and Networked Services (info@dans.knaw.nl)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package nl.knaw.dans.transfer.core;

import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ProviderNotFoundException;

import static nl.knaw.dans.transfer.core.FileUtils.calculateProperties;
import static nl.knaw.dans.transfer.core.FileUtils.findFreeName;
import static nl.knaw.dans.transfer.core.FileUtils.writeProperties;

/**
 * <p>
 * Determines the target NBN of a DVE. If there is no subdirectory for the target NBN yet, one is created. Then the DVE is moved to the subdirectory.
 * </p>
 * <p>
 * The target NBN must be specified in the file <code>DATASETDIR/metadata/oai-ore.jsonld</code> under the JSON Path <code>ore:describes/dansDataVaultMetadata:dansNbn</code>, in which
 * <code>DATASETDIR</code> is the single directory in the root of the DVE. If the task is unable to find the target NBN, it will move the DVE to a subdirectory of the inbox called "failed" and write
 * the stack.
 * </p>
 */
@Slf4j
@AllArgsConstructor
public class CollectDveTask implements Runnable {
    private static final String METADATA_PATH = "metadata/oai-ore.jsonld";
    private static final String NBN_PATH = "$.ore:describes.dansDataVaultMetadata:dansNbn";

    private final Path dve;
    private final Path destinationRoot;
    private final Path failedOutbox;

    @Override
    public void run() {
        try {
            var targetNbn = findTargetNbn();
            var targetDir = destinationRoot.resolve(targetNbn);
            FileUtils.ensureDirectoryExists(targetDir);
            moveToDir(targetDir);
        }
        catch (Exception e) {
            log.error("Unable to process DVE: {}", dve, e);
            try {
                moveToDir(failedOutbox, e);
            }
            catch (IOException ioe) {
                log.error("Unable to move DVE to failed outbox: {}", failedOutbox, ioe);
            }
        }
    }

    private String findTargetNbn() throws IOException {
        try {
            try (FileSystem zipFs = FileSystems.newFileSystem(dve, (ClassLoader) null)) {
                var rootDir = zipFs.getRootDirectories().iterator().next();
                try (var topLevelDirStream = Files.list(rootDir)) {
                    var topLevelDir = topLevelDirStream.filter(Files::isDirectory)
                        .findFirst()
                        .orElseThrow(() -> new IllegalStateException("No top-level directory found in DVE"));

                    var metadataPath = topLevelDir.resolve(METADATA_PATH);
                    if (!Files.exists(metadataPath)) {
                        throw new IllegalStateException("No metadata file found in DVE");
                    }

                    try (var is = Files.newInputStream(metadataPath)) {
                        return JsonPath.read(is, NBN_PATH);
                    }
                    catch (PathNotFoundException e) {
                        throw new IllegalStateException("No NBN found in DVE", e);
                    }
                    catch (Exception e) {
                        throw new IllegalStateException("Unable to read NBN from metadata file", e);
                    }
                }
            }
        }
        catch (ProviderNotFoundException e) {
            throw new RuntimeException("The file system provider is not found. Probably not a ZIP file: " + dve, e);
        }
    }


    private void moveToDir(Path dir) throws IOException {
        moveToDir(dir, null);
    }

    private void moveToDir(Path dir, Exception e) throws IOException {
        var props = calculateProperties(dve);
        var newLocation = findFreeName(dir, dve);
        if (newLocation == null) {
            log.info("File already exists with the same MD5 hash: {}", dve);
            return; // Skip the move if the file already exists with the same MD5
        }
        var newPropertiesFile = newLocation.resolveSibling(newLocation.getFileName() + ".properties");
        writeProperties(newPropertiesFile, props);
        Files.move(dve, dir.resolve(dve.getFileName()));
        if (e != null) {
            writeStackTrace(newLocation, e);
        }
    }

    private void writeStackTrace(Path dve, Exception e) throws IOException {
        var stackTraceFile = dve.resolveSibling(dve.getFileName() + "-error.log");
        try (var writer = Files.newBufferedWriter(stackTraceFile)) {
            e.printStackTrace(new java.io.PrintWriter(writer));
        }
    }
}

