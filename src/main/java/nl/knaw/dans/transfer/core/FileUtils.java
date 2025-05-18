package nl.knaw.dans.transfer.core;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public class FileUtils {
    public static void ensureDirectoryExists(Path dir) throws IOException {
        if (!Files.exists(dir)) {
            Files.createDirectories(dir);
        }
    }

    public static Properties calculateProperties(Path path) throws IOException {
        var props = new Properties();
        props.setProperty("creationTime", getCreationTime(path).toString());
        props.setProperty("md5", calculateMd5(path));
        return props;
    }

    public static Path findFreeName(Path targetDir, Path dve) throws IOException {
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
            if (Files.exists(newPath) && hasSameMd5(dve, newPath)) {
                return null; // Signal that the file already exists with the same MD5
            }
        }
        while (Files.exists(newPath));
        return newPath;
    }

    public static boolean hasSameMd5(Path dve1, Path dve2) throws IOException {
        var props1 = readProperties(dve1.resolveSibling(dve1.getFileName() + ".properties"));
        var props2 = readProperties(dve2.resolveSibling(dve2.getFileName() + ".properties"));
        return props1.getProperty("md5").equals(props2.getProperty("md5"));
    }

    public static Properties readProperties(Path propertiesFile) throws IOException {
        var props = new Properties();
        try (var reader = Files.newBufferedReader(propertiesFile)) {
            props.load(reader);
        }
        return props;
    }

    public static void writeProperties(Path propertiesFile, Properties props) throws IOException {
        try (var writer = Files.newBufferedWriter(propertiesFile)) {
            props.store(writer, "DVE properties");
        }
    }

    public static Object getCreationTime(Path path) throws IOException {
        return Files.getAttribute(path, "creationTime", java.nio.file.LinkOption.NOFOLLOW_LINKS);
    }

    public static String calculateMd5(Path path) throws IOException {
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
