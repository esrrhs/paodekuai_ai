package com.paodekuai.nn;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * 定位与 java/ 平级的 data/ 共享资产(权重、golden 基准)。
 * 从当前目录向上最多 3 层试探,让 Maven(cwd=java/)与 IDE(cwd=仓库根)
 * 都读到同一份 data/。
 */
public final class SharedData {

    private static final int MAX_PARENTS = 3;

    private SharedData() {
    }

    /** 找不到时返回按 cwd 解释的路径,让报错带上文件名 */
    public static Path resolve(String relative) {
        Path found = find(relative);
        if (found != null) {
            return found;
        }
        return Paths.get(relative).toAbsolutePath().normalize();
    }

    public static Path find(String relative) {
        Path cwd = Paths.get("").toAbsolutePath();
        Path dir = cwd;
        for (int i = 0; i <= MAX_PARENTS; ++i) {
            Path candidate = dir.resolve(relative);
            if (Files.exists(candidate)) {
                return candidate;
            }
            Path parent = dir.getParent();
            if (parent == null) {
                break;
            }
            dir = parent;
        }
        return null;
    }

    public static Path require(String relative) throws IOException {
        Path found = find(relative);
        if (found == null) {
            throw new IOException("shared data not found: " + relative
                    + " (searched upward from " + Paths.get("").toAbsolutePath() + ")");
        }
        return found;
    }
}
