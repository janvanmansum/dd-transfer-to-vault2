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

import java.io.IOException;
import java.nio.file.Path;

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
    private final Path dve;
    private final Path destinationRoot;
    private final Path failedOutbox;

    @Override
    public void run() {
        TransferItem transferItem = null;
        try {
            transferItem = new TransferItem(dve);
            var targetNbn = transferItem.readNbn();
            var targetDir = destinationRoot.resolve(targetNbn);
            FileUtils.ensureDirectoryExists(targetDir);
            transferItem.moveToDir(targetDir);
        }
        catch (Exception e) {
            log.error("Unable to process DVE: {}", dve, e);
            try {
                if (transferItem != null) {
                    transferItem.moveToDir(failedOutbox, e);
                }
                else {
                    log.error("TransferItem is null, unable to move DVE to failed outbox");
                }
            }
            catch (IOException ioe) {
                log.error("Unable to move DVE to failed outbox: {}", failedOutbox, ioe);
            }
        }
    }
}

