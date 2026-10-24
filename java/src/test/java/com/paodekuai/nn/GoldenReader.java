package com.paodekuai.nn;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;

/**
 * 读取训练侧 tools/pddump.cpp 导出的 golden 基准(gzip 文本)。
 * 格式见 tools/pddump.cpp 文件头:token 流,数值块每行最多 20 个。
 */
final class GoldenReader {

    record Decision(int turn, int hist, float[] ximp, float[] seq,
                    List<String> optLines, List<float[]> afeat,
                    float[] logits, int chosenSlot, String chosenCards, String actCards) {
    }

    record Game(int index, int lead, List<String> deals, List<Decision> decisions) {
    }

    record Golden(int version, int gameCount, long seed0, List<Game> gameList) {
    }

    private final List<String> tokens;
    private int cursor;

    private GoldenReader(List<String> tokens) {
        this.tokens = tokens;
    }

    static Golden load() throws IOException {
        Path path = SharedData.require("data/golden/golden24.txt.gz");
        try (InputStream raw = Files.newInputStream(path);
             GZIPInputStream gzip = new GZIPInputStream(raw);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(gzip, StandardCharsets.UTF_8))) {
            List<String> tokens = new ArrayList<>(1 << 20);
            String line;
            while ((line = reader.readLine()) != null) {
                for (String t : line.trim().split("\\s+")) {
                    if (!t.isEmpty()) {
                        tokens.add(t);
                    }
                }
            }
            return new GoldenReader(tokens).parse();
        }
    }

    private boolean atEnd() {
        return cursor >= tokens.size();
    }

    private String next() throws IOException {
        if (atEnd()) {
            throw new IOException("unexpected end of golden file");
        }
        return tokens.get(cursor++);
    }

    private int nextInt() throws IOException {
        return Integer.parseInt(next());
    }

    private long nextLong() throws IOException {
        return Long.parseLong(next());
    }

    private float nextFloat() throws IOException {
        return Float.parseFloat(next());
    }

    private float[] nextFloats(int n) throws IOException {
        float[] out = new float[n];
        for (int i = 0; i < n; ++i) {
            out[i] = nextFloat();
        }
        return out;
    }

    private Golden parse() throws IOException {
        expect("PAOGOLDEN");
        int version = nextInt();
        int games = nextInt();
        long seed0 = nextLong();
        List<Game> gameList = new ArrayList<>();
        while (!atEnd()) {
            expect("GAME");
            gameList.add(parseGame());
        }
        return new Golden(version, games, seed0, gameList);
    }

    private void expect(String tag) throws IOException {
        String t = next();
        if (!t.equals(tag)) {
            throw new IOException("expected " + tag + " but got " + t);
        }
    }

    private Game parseGame() throws IOException {
        int index = nextInt();
        List<String> deals = new ArrayList<>(3);
        int lead = -1;
        List<Decision> decisions = new ArrayList<>();
        while (true) {
            String tag = next();
            switch (tag) {
                case "DEAL" -> {
                    nextInt();
                    deals.add(next());
                }
                case "LEAD" -> lead = nextInt();
                case "DEC" -> decisions.add(parseDecision());
                case "ENDGAME" -> {
                    return new Game(index, lead, deals, decisions);
                }
                default -> throw new IOException("unexpected token " + tag);
            }
        }
    }

    private Decision parseDecision() throws IOException {
        int index = nextInt();
        expect("TURN");
        int turn = nextInt();
        expect("HIST");
        int hist = nextInt();
        expect("XIMP");
        int ximpSize = nextInt();
        float[] ximp = nextFloats(ximpSize);
        expect("SEQ");
        int seqSize = nextInt();
        float[] seq = nextFloats(seqSize);
        expect("OPT");
        int opts = nextInt();
        List<String> optLines = new ArrayList<>(opts);
        List<float[]> afeat = new ArrayList<>(opts);
        for (int i = 0; i < opts; ++i) {
            expect("OPTLINE");
            String cards = next();
            optLines.add(cards.equals("-") ? "" : cards);
            expect("AFEAT");
            int n = nextInt();
            afeat.add(nextFloats(n));
        }
        expect("LOGIT");
        int logitCount = nextInt();
        float[] logits = nextFloats(logitCount);
        expect("CHOSEN");
        int slot = nextInt();
        String chosen = next();
        expect("ACT");
        int actSeat = nextInt();
        String act = next();
        chosen = chosen.equals("-") ? "" : chosen;
        act = act.equals("-") ? "" : act;
        expect("ENDDEC");
        if (actSeat != turn) {
            throw new IOException("act seat mismatch at decision " + index);
        }
        return new Decision(turn, hist, ximp, seq, optLines, afeat, logits, slot, chosen, act);
    }
}
