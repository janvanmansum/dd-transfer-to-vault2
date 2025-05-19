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

import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import nl.knaw.dans.transfer.client.VaultCatalogClient;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;

import static nl.knaw.dans.transfer.core.FileUtils.findFreeName;

@Slf4j
@AllArgsConstructor
public class ExtractMetadataTask implements Runnable {
    private final Path targetNbnDir;
    private final Path outboxProcessed;
    private final Path outboxFailed;
    private final Path outboxRejected;
    private final FileContentAttributesReader fileContentAttributesReader;
    private final VaultCatalogClient vaultCatalogClient;

    @Override
    public void run() {
        log.debug("Started ExtractMetadataTask for {}", targetNbnDir);
        try {
            if (isBlocked()) {
                log.debug("Target directory {} is blocked, skipping", targetNbnDir);
                return;
            }
        }
        catch (IOException e) {
            log.error("Unable to check if target directory is blocked. Aborting...", e);
            return;
        }

        try {
            var dves = getDves();
            while (!dves.isEmpty()) {
                for (var dve : dves) {
                    try {
                        var fileContenctAttributes = fileContentAttributesReader.getFileContentAttributes(dve);
                        vaultCatalogClient.registerOcflObjectVersion(fileContenctAttributes);
                        moveToDir(dve, outboxProcessed, null);
                    }
                    catch (Exception e) {
                        log.error("Error processing DVE", e);
                        moveToDir(dve, outboxFailed, e);
                        try {
                            blockTarget();
                        }
                        catch (IOException ioe) {
                            log.error("Unable to block target directory", ioe);
                        }
                    }
                    // Get any new DVE files that may have been added while processing
                    dves = getDves();
                }
            }
        }
        catch (Exception e) {
            log.error("Error processing DVE files", e);
            try {
                blockTarget();
            }
            catch (IOException ioe) {
                log.error("Unable to block target directory", ioe);
            }
        }
        finally {
            log.debug("Finished ExtractMetadataTask for {}", targetNbnDir);
        }
        /*
         * At this point, all DVE files in targetNbnDir have been processed. We are NOT sticking around to wait for more DVEs, as this would prevent our worker thread from taking on other tasks.
         * The transfer inbox will dete targetNbnDir if it is empty in the next polling cycle. If new DVEs are added for this same NBN, the inbox will create a new targetNbnDir (with a different name)
         * and create a new ExtractMetadataTask for it. It cannot have the same name, because there would be two tasks instances competing for the same targetNbnDir.
         */
    }

    private List<Path> getDves() throws IOException {
        try (var dirStream = Files.list(targetNbnDir)) {
            return dirStream.filter(Files::isRegularFile).filter(p -> p.getFileName().toString().endsWith(".zip"))
                .sorted(CreationTimeComparator.getInstance()).toList();
        }
    }

    private boolean isBlocked() throws IOException {
        return Files.exists(targetNbnDir.resolve("block"));
    }

    private void blockTarget() throws IOException {
        if (!isBlocked()) {
            Files.createFile(targetNbnDir.resolve("block"));
        }
    }

    private void moveToDir(Path dve, Path dir, Exception e) throws IOException {
        var newLocation = findFreeName(dir, dve);
        if (newLocation == null) {
            log.info("File already exists with the same MD5 hash: {}", dve);
            return; // Skip the move if the file already exists with the same MD5
        }
        var sourcePropertiesFile = dve.resolveSibling(dve.getFileName() + ".properties");
        var targetPropertiesFile = newLocation.resolveSibling(newLocation.getFileName() + ".properties");
        Files.move(sourcePropertiesFile, targetPropertiesFile, StandardCopyOption.REPLACE_EXISTING);
        Files.move(dve, dir.resolve(dve.getFileName()));
        if (e != null) {
            FileUtils.writeStackTrace(newLocation, e);
        }
    }
}
