// 内部实现细节: 读 TorchScript 权重档 (zip) 与 actor 的 manifest.json。
//
// 库不依赖 libtorch, 也不依赖 zlib: tools/export_actor.py 把张量数据以 STORED(不压缩)
// 形式写成 <root>/data/<i>, 条目序号 -> 层名 -> 形状 的映射记在 <root>_manifest.json 里,
// 所以只要自己解析 zip 中央目录, 再按清单取原始小端 float32 字节即可。
#pragma once

#include <cstdint>
#include <string>
#include <vector>

namespace paodekuai
{
namespace detail
{

struct ZipEntry
{
    std::string name;
    std::uint16_t method = 0; // 0 = STORED
    std::uint64_t compSize = 0;
    std::uint64_t uncompSize = 0;
    std::uint64_t dataOffset = 0; // 数据起始位置 (本地文件头之后)
};

// 只读、只支持 STORED 条目的最小 zip 读取器
class ZipReader
{
public:
    bool Open(const std::string &path, std::string *err);

    const std::vector<ZipEntry> &Entries() const { return entries_; }
    const ZipEntry *Find(const std::string &name) const;

    // 取条目原始字节 (仅 STORED 可用); 不支持时返回 nullptr 并写 err
    const std::uint8_t *StoredData(const ZipEntry &entry, std::uint64_t *size, std::string *err) const;

private:
    std::vector<char> blob_;
    std::vector<ZipEntry> entries_;
};

// manifest.json 里一条参数记录
struct ParamSpec
{
    int entry = -1;
    std::string name;
    std::vector<int> shape;
};

// 解析 actor manifest: 校验 dtype/byteOrder, 取出 root 与 entry -> 层名 -> 形状
bool ParseManifest(const std::string &text, std::string *root, std::vector<ParamSpec> *params,
                   std::string *err);

} // namespace detail
} // namespace paodekuai
