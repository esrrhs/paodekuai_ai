// Hand-written forward pass over the exported TorchScript actor weights —
// no libtorch, no third-party dependency: weights are STORED (uncompressed)
// zip entries parsed with the standard library only (see src/pt_archive.h).
#pragma once

#include <array>
#include <memory>
#include <string>
#include <vector>

#include "paodekuai/perfect_pao_features.h"
#include "paodekuai/public_view.h"

namespace paodekuai {

inline constexpr int kPolicyLstmHidden = 128;
inline constexpr int kPolicyLstmGates = 4 * kPolicyLstmHidden;  // 512
inline constexpr int kPolicyNodeDim = kNodeSize + kPolicyLstmHidden;       // 1316
inline constexpr int kPolicyJointSize = kPolicyNodeDim + kActionSize;      // 1465
inline constexpr int kPolicyHidden = 256;
inline constexpr int kPolicyWide = 512;

class Policy {
public:
    Policy();
    ~Policy();  // Actor is incomplete in the header

    Policy(Policy &&) = delete;
    Policy &operator=(Policy &&) = delete;

    // Loads pao_ts.pt + pao_manifest.json from `dir`.
    bool Load(const std::string &dir);
    const std::string &Error() const { return error_; }

    static const char *DefaultModelRel() { return "data/models/ts"; }
    static std::string DefaultModelDir();

    // Scores `options` candidate actions.
    //   xImp:       kNodeSize floats
    //   seq:        kLstmSteps * kLstmIn floats (row-major [5][429])
    //   actionFeat: options * kActionSize floats, row-major
    // Returns one logit per candidate slot.
    std::vector<float> Score(const float *xImp, const float *seq,
                             const float *actionFeat, int options) const;

    struct Decision {
        CardSet move;                 // chosen concrete move (empty = pass)
        int slot = -1;                // chosen option index
        std::vector<CardSet> options; // candidate concrete moves ("" pass)
        std::vector<float> logits;    // per candidate
        std::vector<float> probs;     // softmax over candidates (display only)
    };

    // Full decision path: encode -> enumerate -> score -> argmax.
    Decision Decide(const PublicView &view) const;

private:
    struct Actor;
    std::unique_ptr<Actor> actor_;
    std::string error_;
};

}  // namespace paodekuai
