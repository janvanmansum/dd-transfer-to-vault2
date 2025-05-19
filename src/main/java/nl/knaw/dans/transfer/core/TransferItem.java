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
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.ProviderNotFoundException;
import java.util.Properties;

/**
 * A Dataset Version Export (DVE) and auxiliary files. The DVE is the only mandatory file. The other files are searched next to the DVE or constructed from the DVE. This class is intended to provide
 * lightweight access to the DVE and its metadata, and not to be a full-fledged representation of the DVE.
 */
@Slf4j
public class TransferItem {
    private static final String METADATA_PATH = "metadata/oai-ore.jsonld";
    private static final String NBN_PATH = "$.ore:describes.dansDataVaultMetadata:dansNbn";
    private final DveMetadataReader dveMetadataReader;

    private Path dve;
    private Path properties;

    public TransferItem(Path dve, DveMetadataReader dveMetadataReader) {
        this.dve = dve;
        this.dveMetadataReader = dveMetadataReader;
        this.properties = initProperties(dve);
    }

    public TransferItem(Path dve) {
        this(dve, null);
    }

    private static Path initProperties(Path dve) {
        try {
            var properties = dve.resolveSibling(dve.getFileName() + ".properties");
            if (Files.notExists(properties)) {
                var props = new Properties();
                props.setProperty("creationTime", getCreationTime(dve).toString());
                props.setProperty("md5", calculateMd5(dve));
                // Save
                try (var out = Files.newOutputStream(properties)) {
                    props.store(out, null);
                }
            }
            return properties;
        }
        catch (IOException e) {
            throw new IllegalStateException("Failed to initialize properties file", e);
        }
    }

    /**
     * Moves the DVE to a new directory. If the file already exists with the same MD5 hash, it will be deleted.
     *
     * @param dir the directory to move the DVE to
     * @param e   the exception to log in the error log file
     * @throws IOException if an I/O error occurs
     */
    public void moveToDir(Path dir, Exception e) throws IOException {
        var newLocation = findFreeName(dir, dve);
        var newPropertiesFile = newLocation.resolveSibling(newLocation.getFileName() + ".properties");
        if (Files.exists(newLocation)) {
            log.error("File already exists: {}", newLocation);
        }
        else {
            Files.move(properties, newPropertiesFile);
            Files.move(dve, newLocation);
            dve = newLocation;
            properties = newPropertiesFile;
        }
        if (e != null) {
            var errorLogFile = newLocation.resolveSibling(newLocation.getFileName() + "-error.log");
            writeStackTrace(errorLogFile, e);
        }
    }

    private static void writeStackTrace(Path errorLog, Exception e) throws IOException {
        try (var writer = Files.newBufferedWriter(errorLog)) {
            e.printStackTrace(new java.io.PrintWriter(writer));
        }
    }

    private Path findFreeName(Path targetDir, Path dve) throws IOException {
        var fileName = dve.getFileName().toString();
        var dotIndex = fileName.lastIndexOf('.');
        var baseName = (dotIndex == -1) ? fileName : fileName.substring(0, dotIndex);
        var extension = (dotIndex == -1) ? "" : fileName.substring(dotIndex);
        var sequenceNumber = 0;
        Path newPath;
        do {
            if (sequenceNumber == 0) {
                newPath = targetDir.resolve(baseName + extension);
            }
            else {
                newPath = targetDir.resolve(baseName + "-" + sequenceNumber + extension);
            }
            sequenceNumber++;
            if (Files.exists(newPath) && new TransferItem(newPath).getProperty("md5").equals(getProperty("md5"))) {
                break;
            }
        }
        while (Files.exists(newPath));
        return newPath;
    }

    public void moveToDir(Path dir) throws IOException {
        moveToDir(dir, null);
    }

    public void setProperty(String key, String value) {
        try {
            var props = new Properties();
            if (Files.exists(properties)) {
                try (var in = Files.newInputStream(properties)) {
                    props.load(in);
                }
            }
            props.setProperty(key, value);
            try (var out = Files.newOutputStream(properties)) {
                props.store(out, null);
            }
        }
        catch (Exception ex) {
            throw new RuntimeException("Failed to set property", ex);
        }
    }

    public String getProperty(String key) {
        try {
            var props = new java.util.Properties();
            if (properties != null && Files.exists(properties)) {
                try (var in = Files.newInputStream(properties)) {
                    props.load(in);
                }
            }
            return props.getProperty(key);
        }
        catch (Exception ex) {
            throw new RuntimeException("Failed to get property", ex);
        }
    }

    public int getOcflObjectVersion() {
        Object ocflObjectVersion = getProperty("ocflObjectVersion");
        if (ocflObjectVersion == null) {
            return -1;
        }
        else {
            try {
                return Integer.parseInt(ocflObjectVersion.toString());
            }
            catch (NumberFormatException e) {
                throw new IllegalStateException("Invalid OCFL object version: " + ocflObjectVersion, e);
            }
        }
    }

    public String readNbn() throws IOException {
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

    public DveMetadata readMetadata() {
        if (dveMetadataReader == null) {
            throw new IllegalStateException("FileContentAttributesReader is not initialized");
        }
        return dveMetadataReader.getFileContentAttributes(dve);
    }

    private static Object getCreationTime(Path path) throws IOException {
        return Files.getAttribute(path, "creationTime", java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }

    private static String calculateMd5(Path path) throws IOException {
        try {
            var md5 = java.security.MessageDigest.getInstance("MD5");
            try (var is = Files.newInputStream(path)) {
                var digest = md5.digest(is.readAllBytes());
                var hexString = new StringBuilder();
                for (byte b : digest) {
                    hexString.append(String.format("%02x", b));
                }
                return hexString.toString();
            }
        }
        catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("MD5 algorithm not found", e);
        }
    }
}
