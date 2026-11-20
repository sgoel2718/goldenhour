package com.goldenhour.infra;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only write-ahead log (one JSON event per line).
 *
 * <p>Every state change (incident received, unit assigned, unit released) is appended and flushed BEFORE
 * the API acknowledges it. After a crash the service replays the log to rebuild in-memory state, and the
 * idempotency keys it contains make client retries safe ("exactly-once effect" on top of at-least-once
 * delivery). With {@code fsync=true} each append is forced to disk (durable but slower); production would
 * batch fsyncs (group commit) or ship the log to Kafka.
 */
public final class WriteAheadLog implements AutoCloseable {
    private final Path path;
    private final BufferedWriter out;
    private final FileChannel channel;
    private final boolean fsync;
    private long appended;

    public WriteAheadLog(Path path, boolean fsync) throws IOException {
        this.path = path;
        this.fsync = fsync;
        if (path.getParent() != null) Files.createDirectories(path.getParent());
        channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        out = new BufferedWriter(new java.io.OutputStreamWriter(java.nio.channels.Channels.newOutputStream(channel), StandardCharsets.UTF_8));
    }

    public synchronized void append(String jsonLine) throws IOException {
        if (jsonLine.indexOf('\n') >= 0) throw new IllegalArgumentException("one event per line");
        out.write(jsonLine);
        out.write('\n');
        out.flush();
        if (fsync) channel.force(false);
        appended++;
    }

    public synchronized long appended() { return appended; }

    /** All events in the order they were written. A torn last line (crash mid-write) is ignored. */
    public static List<String> replay(Path path) throws IOException {
        List<String> lines = new ArrayList<>();
        if (!Files.exists(path)) return lines;
        for (String l : Files.readAllLines(path, StandardCharsets.UTF_8)) if (l.startsWith("{") && l.endsWith("}")) lines.add(l);
        return lines;
    }

    public Path path() { return path; }

    @Override
    public synchronized void close() throws IOException { out.close(); }
}
