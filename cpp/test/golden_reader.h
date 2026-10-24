// Parses the golden fixture dumped by tools/pddump.cpp (gzip text).
// Format: see tools/pddump.cpp header comment.
#pragma once

#include <array>
#include <cstdint>
#include <cstdio>
#include <cstring>
#include <string>
#include <vector>

#include "paodekuai/cards.h"

namespace paodekuai::test {

struct GoldenDecision {
    int turn = 0;
    int hist = 0;
    std::vector<float> ximp;
    std::vector<float> seq;
    std::vector<std::string> optLines;  // "-" denotes a pass
    std::vector<std::vector<float>> afeat;
    std::vector<float> logits;
    int chosenSlot = -1;
    std::string chosenCards;
    std::string actCards;
};

struct GoldenGame {
    int index = 0;
    int lead = 0;
    std::array<std::string, 3> deals;
    std::vector<GoldenDecision> decisions;
};

struct Golden {
    int version = 0;
    int games = 0;
    std::uint64_t seed0 = 0;
    std::vector<GoldenGame> gameList;
};

// Reads a plain-text golden dump; gzip decompression is handled by the caller
// (the test pipes through zlib when available).
inline bool ReadGoldenStream(std::FILE *fp, Golden *out) {
    std::vector<std::string> tokens;
    char buf[4096];
    while (std::fgets(buf, sizeof buf, fp)) {
        char *save = nullptr;
        for (char *t = strtok_r(buf, " \t\r\n", &save); t;
             t = strtok_r(nullptr, " \t\r\n", &save)) {
            tokens.emplace_back(t);
        }
    }
    size_t cur = 0;
    auto next = [&]() -> const std::string & {
        if (cur >= tokens.size()) {
            std::fprintf(stderr, "golden: unexpected end of tokens\n");
            std::exit(1);
        }
        return tokens[cur++];
    };
    auto nextInt = [&]() { return std::atoi(next().c_str()); };
    auto nextLong = [&]() { return std::strtoull(next().c_str(), nullptr, 10); };
    auto nextFloat = [&]() { return std::strtof(next().c_str(), nullptr); };
    auto expect = [&](const char *tag) {
        const std::string &t = next();
        if (t != tag) {
            std::fprintf(stderr, "golden: expected %s but got %s\n", tag, t.c_str());
            std::exit(1);
        }
    };
    auto nextFloats = [&](std::vector<float> *dst, int n) {
        dst->resize(n);
        for (int i = 0; i < n; ++i) (*dst)[i] = nextFloat();
    };

    expect("PAOGOLDEN");
    out->version = nextInt();
    out->games = nextInt();
    out->seed0 = nextLong();
    while (cur < tokens.size()) {
        expect("GAME");
        GoldenGame game;
        game.index = nextInt();
        while (true) {
            const std::string tag = next();
            if (tag == "DEAL") {
                int seat = nextInt();
                game.deals[seat] = next();
            } else if (tag == "LEAD") {
                game.lead = nextInt();
            } else if (tag == "DEC") {
                GoldenDecision dec;
                nextInt();  // decision index
                expect("TURN");
                dec.turn = nextInt();
                expect("HIST");
                dec.hist = nextInt();
                expect("XIMP");
                int n = nextInt();
                nextFloats(&dec.ximp, n);
                expect("SEQ");
                n = nextInt();
                nextFloats(&dec.seq, n);
                expect("OPT");
                int opts = nextInt();
                for (int i = 0; i < opts; ++i) {
                    expect("OPTLINE");
                    std::string cards = next();
                    dec.optLines.push_back(cards == "-" ? "" : cards);
                    expect("AFEAT");
                    int an = nextInt();
                    std::vector<float> feat;
                    nextFloats(&feat, an);
                    dec.afeat.push_back(std::move(feat));
                }
                expect("LOGIT");
                int ln = nextInt();
                nextFloats(&dec.logits, ln);
                expect("CHOSEN");
                dec.chosenSlot = nextInt();
                dec.chosenCards = next();
                if (dec.chosenCards == "-") dec.chosenCards.clear();
                expect("ACT");
                nextInt();  // seat
                dec.actCards = next();
                if (dec.actCards == "-") dec.actCards.clear();
                expect("ENDDEC");
                game.decisions.push_back(std::move(dec));
            } else if (tag == "ENDGAME") {
                break;
            } else {
                std::fprintf(stderr, "golden: unexpected token %s\n", tag.c_str());
                std::exit(1);
            }
        }
        out->gameList.push_back(std::move(game));
    }
    return true;
}

}  // namespace paodekuai::test
