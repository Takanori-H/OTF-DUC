package ltsa.updatingControllers.cli;

import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

import ltsa.lts.LTSOutput;

final class CliFileLTSOutput implements LTSOutput, Closeable {

    private final File file;
    private BufferedWriter writer;

    CliFileLTSOutput(File file) {
        this.file = file;
    }

    @Override
    public synchronized void out(String str) {
        try {
            ensureWriter(true);
            writer.write(str == null ? "" : str);
            writer.flush();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write output file: " + file, e);
        }
    }

    @Override
    public synchronized void outln(String str) {
        try {
            ensureWriter(true);
            writer.write(str == null ? "" : str);
            writer.newLine();
            writer.flush();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to write output file: " + file, e);
        }
    }

    @Override
    public synchronized void clearOutput() {
        try {
            close();
            ensureParentDirectory(file);
            writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(file, false),
                    StandardCharsets.UTF_8));
            writer.flush();
        } catch (IOException e) {
            throw new IllegalStateException("Failed to clear output file: " + file, e);
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (writer != null) {
            writer.close();
            writer = null;
        }
    }

    private void ensureWriter(boolean append) throws IOException {
        if (writer == null) {
            ensureParentDirectory(file);
            writer = new BufferedWriter(new OutputStreamWriter(
                    new FileOutputStream(file, append),
                    StandardCharsets.UTF_8));
        }
    }

    static void ensureParentDirectory(File file) throws IOException {
        File parent = file.getAbsoluteFile().getParentFile();
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw new IOException("Failed to create directory: " + parent);
        }
    }
}
