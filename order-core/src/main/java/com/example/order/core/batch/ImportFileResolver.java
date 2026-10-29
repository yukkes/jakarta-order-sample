package com.example.order.core.batch;

import com.example.order.core.security.SecuritySettings;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

/**
 * Resolves a client-supplied import file name inside the configured import
 * directory. Only a bare file name is accepted, and the resolved real path must
 * stay inside the directory, so neither {@code ../} nor symlinks can reach other
 * files on the server.
 */
@ApplicationScoped
public class ImportFileResolver {

    private static final Pattern FILE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,100}\\.csv");

    @Inject
    private SecuritySettings settings;

    /**
     * @throws IllegalArgumentException if the name is not allowed or the file does not exist
     */
    public Path resolve(String fileName) {
        if (fileName == null || !FILE_NAME.matcher(fileName).matches()) {
            throw new IllegalArgumentException(
                    "入力ファイルは英数字・. _ - だけからなる .csv のファイル名で指定してください");
        }

        Path base = settings.importDir().toAbsolutePath().normalize();
        Path candidate = base.resolve(fileName).normalize();
        if (!candidate.startsWith(base) || !Files.isRegularFile(candidate)) {
            throw notFound(fileName);
        }
        try {
            Path real = candidate.toRealPath();
            if (!real.startsWith(base.toRealPath())) {
                throw notFound(fileName);
            }
            return real;
        } catch (IOException exception) {
            throw notFound(fileName);
        }
    }

    private IllegalArgumentException notFound(String fileName) {
        return new IllegalArgumentException("取込ディレクトリに入力ファイルがありません: " + fileName);
    }
}
