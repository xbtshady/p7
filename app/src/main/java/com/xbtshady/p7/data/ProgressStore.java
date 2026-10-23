package com.xbtshady.p7.data;

import android.content.Context;
import android.util.Log;

import com.xbtshady.p7.model.Progress;
import com.xbtshady.p7.model.Record;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Set;

/**
 * progress.json 的读写（架构文档 §6）。
 *
 * <p>路径：{@code getExternalMediaDirs()[0] + /p7/progress.json}。
 * 为什么在这：app 私有目录和 Android/data/&lt;包名&gt;/ 会随卸载删除，这个目录不会，且 API 29+ 写它免权限。
 */
public final class ProgressStore {

    private static final String TAG = "p7";
    private static final String DIR = "p7";
    private static final String FILE = "progress.json";

    private File file;
    /** 降级到 getFilesDir() 时为 true：卸载会丢，仅日志 */
    private boolean volatileOnly;

    public void init(Context ctx) {
        file = null;
        volatileOnly = false;
        try {
            File[] dirs = ctx.getExternalMediaDirs();
            if (dirs != null && dirs.length > 0 && dirs[0] != null) {
                File d = new File(dirs[0], DIR);
                if (d.isDirectory() || d.mkdirs()) {
                    file = new File(d, FILE);
                }
            }
        } catch (Throwable t) {
            Log.w(TAG, "外部 media 目录不可用", t);
        }
        if (file == null) {
            // §6 降级
            File d = new File(ctx.getFilesDir(), DIR);
            if (d.isDirectory() || d.mkdirs()) file = new File(d, FILE);
            volatileOnly = true;
            Log.w(TAG, "降级到内部目录，卸载会丢进度");
        }
    }

    public boolean isVolatileOnly() {
        return volatileOnly;
    }

    public File path() {
        return file;
    }

    /**
     * 读。解析异常则备份为 progress.bad.json 后新建，不崩溃（§6）。
     * 之后再按 §6.1 做版本迁移。
     */
    public Progress load(int currentBankVersion, Set<String> validQids) {
        Progress p;
        try {
            if (file == null || !file.exists()) {
                p = new Progress();
            } else {
                String json = readFile(file);
                p = Progress.from(new JSONObject(json));
            }
        } catch (Throwable t) {
            Log.w(TAG, "进度文件解析失败，备份后新建", t);
            backupBad();
            p = new Progress();
        }
        migrate(p, currentBankVersion, validQids);
        return p;
    }

    /** §6.1 版本迁移 */
    private void migrate(Progress p, int currentBankVersion, Set<String> validQids) {
        if (p.schemaVersion > Progress.SCHEMA_VERSION) {
            // 高于当前：视为不可读，备份 + 新建。只保留 auxTier，成绩全部作废
            Log.w(TAG, "progress schemaVersion " + p.schemaVersion + " 高于当前，重建");
            backupBad();
            String keepTier = p.auxTier;
            p.records.clear();
            p.lastQid = null;
            p.auxTier = keepTier;
            p.schemaVersion = Progress.SCHEMA_VERSION;
            p.bankVersion = currentBankVersion;
            return;
        }
        // 低于当前：逐版升级。目前只有 v1，无迁移方法需要执行
        p.schemaVersion = Progress.SCHEMA_VERSION;

        if (p.bankVersion != currentBankVersion) {
            // 保留 auxTier；records 按 qid 过滤，只留题库里还存在的题
            Log.i(TAG, "bankVersion " + p.bankVersion + " -> " + currentBankVersion + "，过滤失效记录");
            if (validQids != null) {
                Iterator<Record> it = p.records.iterator();
                while (it.hasNext()) {
                    if (!validQids.contains(it.next().qid)) it.remove();
                }
            }
            p.bankVersion = currentBankVersion;
        }
    }

    /** §6 原子写：写 progress.json.tmp → renameTo */
    public boolean save(Progress p) {
        if (file == null) return false;
        try {
            File tmp = new File(file.getParentFile(), FILE + ".tmp");
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(p.toJson().toString(2).getBytes(StandardCharsets.UTF_8));
                out.flush();
                out.getFD().sync();
            }
            File bak = new File(file.getParentFile(), FILE + ".old");
            if (file.exists() && !file.renameTo(bak)) {
                Log.w(TAG, "旧进度备份失败，继续覆盖");
            }
            if (!tmp.renameTo(file)) {
                Log.e(TAG, "renameTo 失败");
                return false;
            }
            if (bak.exists()) bak.delete();
            return true;
        } catch (Throwable t) {
            Log.e(TAG, "进度写入失败", t);
            return false;
        }
    }

    private void backupBad() {
        if (file == null || !file.exists()) return;
        try {
            File bad = new File(file.getParentFile(), "progress.bad.json");
            File bak = new File(file.getParentFile(), FILE + ".old");
            if (bak.exists()) bak.delete();
            file.renameTo(bad);
        } catch (Throwable t) {
            Log.w(TAG, "坏文件备份失败", t);
        }
    }

    private static String readFile(File f) throws Exception {
        try (InputStream in = new java.io.FileInputStream(f)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bos.write(buf, 0, n);
            return new String(bos.toByteArray(), StandardCharsets.UTF_8);
        }
    }
}
