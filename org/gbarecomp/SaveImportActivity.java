package org.gbarecomp;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Process;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Import saves through the Storage Access Framework (no storage permission).
 *
 * Accepts a raw 32768-byte battery save (.sav) or a ZIP made by "Export saves"
 * (entries: saves/SacredStonesRecomp.sav, roms/sacred_stones_usa.state1..9,
 * roms/sacred_stones_usa.suspend.state, backup.json).
 *
 * Files are written into getFilesDir() (the same layout SaveArchive reads).
 * The old automatic suspend snapshot is moved aside unless the ZIP carries one,
 * because the game resumes from it on launch and it would shadow the imported
 * battery save. After OK the app process is ended so the next launch is clean.
 */
public final class SaveImportActivity extends Activity {
    private static final int REQ_PICK = 4711;
    static final int SAV_SIZE = 32768;
    private static final long MAX_STATE = 16L * 1024 * 1024; // same cap as SaveArchive
    static final String SAV = "saves/SacredStonesRecomp.sav";
    static final String SUSPEND = "roms/sacred_stones_usa.suspend.state";
    private static final Pattern STATE =
        Pattern.compile("roms/sacred_stones_usa\\.state[1-9]");

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        if (savedInstanceState != null) return; // picker already open / result pending
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        startActivityForResult(intent, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            finish();
            return;
        }
        try {
            byte[] blob = readAll(data.getData());
            int count = importBlob(getFilesDir(), blob);
            done("Saves imported", "Imported " + count + " file" + (count == 1 ? "" : "s")
                + ". The app will close; open it again to play.", true);
        } catch (Exception e) {
            done("Import failed", String.valueOf(e.getMessage()), false);
        }
    }

    private void done(String title, String message, boolean kill) {
        if (isFinishing() || isDestroyed()) return;
        new AlertDialog.Builder(this).setTitle(title).setMessage(message)
            .setCancelable(false)
            .setPositiveButton("OK", (d, w) -> {
                finish();
                if (kill) Process.killProcess(Process.myPid());
            }).show();
    }

    /** Validates everything first, then writes. Returns the number of files written. */
    static int importBlob(File root, byte[] blob) throws IOException {
        List<String> names = new ArrayList<>();
        List<byte[]> datas = new ArrayList<>();
        if (blob.length == SAV_SIZE) {
            names.add(SAV);
            datas.add(blob);
        } else if (blob.length >= 4 && blob[0] == 'P' && blob[1] == 'K') {
            try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(blob))) {
                ZipEntry entry;
                while ((entry = zip.getNextEntry()) != null) {
                    String name = entry.getName();
                    boolean sav = name.equals(SAV);
                    if (!sav && !STATE.matcher(name).matches() && !name.equals(SUSPEND)) continue;
                    byte[] data = readStream(zip, sav ? SAV_SIZE : MAX_STATE);
                    if (sav ? data.length != SAV_SIZE : data.length < 1)
                        throw new IOException("Unexpected save size: " + name);
                    if (names.contains(name)) throw new IOException("Duplicate entry: " + name);
                    names.add(name);
                    datas.add(data);
                }
            }
            if (names.isEmpty()) throw new IOException("The ZIP contains no saves from this game.");
        } else {
            throw new IOException("Choose a 32 KiB .sav file or an exported ZIP.");
        }
        // The game resumes from this snapshot on launch; drop the stale one unless replaced.
        if (!names.contains(SUSPEND)) moveAside(new File(root, SUSPEND));
        for (int i = 0; i < names.size(); i++) write(root, names.get(i), datas.get(i));
        return names.size();
    }

    private static void moveAside(File file) throws IOException {
        if (!file.exists()) return;
        File bak = new File(file.getPath() + ".bak");
        if (bak.exists() && !bak.delete()) throw new IOException("Cannot replace " + bak.getName());
        if (!file.renameTo(bak)) throw new IOException("Cannot move aside " + file.getName());
    }

    /** temp file + rename; previous file kept as .bak. */
    private static void write(File root, String relative, byte[] data) throws IOException {
        File dest = new File(root, relative);
        if (!dest.getCanonicalPath().startsWith(root.getCanonicalPath() + File.separator))
            throw new IOException("Unsafe save path: " + relative);
        File dir = dest.getParentFile();
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("Cannot create " + dir.getName());
        File tmp = new File(dir, dest.getName() + ".import.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(data);
            out.getFD().sync();
        }
        moveAside(dest);
        if (!tmp.renameTo(dest)) throw new IOException("Cannot write " + dest.getName());
    }

    private byte[] readAll(Uri uri) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IOException("The file cannot be opened.");
            return readStream(in, 160L * 1024 * 1024);
        }
    }

    private static byte[] readStream(InputStream in, long cap) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[65536];
        long total = 0;
        int n;
        while ((n = in.read(buffer)) != -1) {
            total += n;
            if (total > cap) throw new IOException("The file is too large.");
            out.write(buffer, 0, n);
        }
        return out.toByteArray();
    }
}
