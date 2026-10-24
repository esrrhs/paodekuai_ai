// TorchScript 权重档与 manifest 的最小解析实现。
#include "pt_archive.h"

#include <cctype>
#include <cstdio>
#include <cstring>

namespace paodekuai
{
namespace detail
{
namespace
{

std::uint16_t Le16(const char *p)
{
    return static_cast<std::uint16_t>(static_cast<unsigned char>(p[0]) |
                                     (static_cast<unsigned char>(p[1]) << 8));
}

std::uint32_t Le32(const char *p)
{
    return static_cast<std::uint32_t>(static_cast<unsigned char>(p[0])) |
           (static_cast<std::uint32_t>(static_cast<unsigned char>(p[1])) << 8) |
           (static_cast<std::uint32_t>(static_cast<unsigned char>(p[2])) << 16) |
           (static_cast<std::uint32_t>(static_cast<unsigned char>(p[3])) << 24);
}

bool IsSignature(const char *p, const char *sig)
{
    return std::memcmp(p, sig, 4) == 0;
}

// 跳过空白
std::size_t SkipSpace(const std::string &t, std::size_t i)
{
    while (i < t.size() && (std::isspace(static_cast<unsigned char>(t[i])) != 0))
    {
        ++i;
    }
    return i;
}

// 找到 "key" 之后的冒号位置; 找不到返回 npos
std::size_t FindKeyValueStart(const std::string &t, std::size_t from, const std::string &key)
{
    const std::string needle = "\"" + key + "\"";
    std::size_t pos = t.find(needle, from);
    if (pos == std::string::npos)
    {
        return std::string::npos;
    }
    pos = SkipSpace(t, pos + needle.size());
    if (pos >= t.size() || t[pos] != ':')
    {
        return std::string::npos;
    }
    return SkipSpace(t, pos + 1);
}

bool ExtractStringValue(const std::string &t, const std::string &key, std::string *out)
{
    std::size_t pos = FindKeyValueStart(t, 0, key);
    if (pos == std::string::npos || pos >= t.size() || t[pos] != '"')
    {
        return false;
    }
    ++pos;
    std::string value;
    while (pos < t.size() && t[pos] != '"')
    {
        if (t[pos] == '\\' && pos + 1 < t.size())
        {
            ++pos;
        }
        value.push_back(t[pos++]);
    }
    if (pos >= t.size())
    {
        return false;
    }
    *out = value;
    return true;
}

bool ExtractIntValue(const std::string &t, const std::string &key, int *out)
{
    std::size_t pos = FindKeyValueStart(t, 0, key);
    if (pos == std::string::npos)
    {
        return false;
    }
    std::size_t begin = pos;
    if (begin < t.size() && (t[begin] == '-' || t[begin] == '+'))
    {
        ++begin;
    }
    std::size_t end = begin;
    while (end < t.size() && std::isdigit(static_cast<unsigned char>(t[end])) != 0)
    {
        ++end;
    }
    if (end == begin)
    {
        return false;
    }
    *out = std::stoi(t.substr(begin, end - begin));
    return true;
}

// 解析 [a, b] 形式的整数数组; 返回右括号之后的位置, 失败返回 npos
std::size_t ExtractIntArray(const std::string &t, std::size_t from, std::vector<int> *out)
{
    std::size_t pos = SkipSpace(t, from);
    if (pos >= t.size() || t[pos] != '[')
    {
        return std::string::npos;
    }
    ++pos;
    for (;;)
    {
        pos = SkipSpace(t, pos);
        if (pos >= t.size())
        {
            return std::string::npos;
        }
        if (t[pos] == ']')
        {
            return pos + 1;
        }
        std::size_t begin = pos;
        std::size_t end = begin;
        while (end < t.size() && std::isdigit(static_cast<unsigned char>(t[end])) != 0)
        {
            ++end;
        }
        if (end == begin)
        {
            return std::string::npos;
        }
        out->push_back(std::stoi(t.substr(begin, end - begin)));
        pos = SkipSpace(t, end);
        if (pos < t.size() && t[pos] == ',')
        {
            ++pos;
        }
    }
}

} // namespace

bool ZipReader::Open(const std::string &path, std::string *err)
{
    entries_.clear();
    std::FILE *fp = std::fopen(path.c_str(), "rb");
    if (fp == nullptr)
    {
        if (err)
        {
            *err = "打不开 " + path;
        }
        return false;
    }
    std::fseek(fp, 0, SEEK_END);
    const long sz = std::ftell(fp);
    std::fseek(fp, 0, SEEK_SET);
    if (sz <= 0)
    {
        std::fclose(fp);
        if (err)
        {
            *err = "文件为空 " + path;
        }
        return false;
    }
    blob_.resize(static_cast<std::size_t>(sz));
    const std::size_t got = std::fread(blob_.data(), 1, blob_.size(), fp);
    std::fclose(fp);
    if (got != blob_.size())
    {
        if (err)
        {
            *err = "读取不完整 " + path;
        }
        return false;
    }

    // 从尾部找 EOCD (允许最多 64KB 注释)
    const std::size_t n = blob_.size();
    if (n < 22)
    {
        if (err)
        {
            *err = "文件太小, 不是 zip 档: " + path;
        }
        return false;
    }
    const std::size_t lowest = n > 65557 ? n - 65557 : 0;
    std::size_t eocd = std::string::npos;
    for (std::size_t i = n - 4 + 1; i > lowest; --i)
    {
        if (IsSignature(blob_.data() + i - 1, "PK\x05\x06"))
        {
            eocd = i - 1;
            break;
        }
    }
    if (eocd == std::string::npos || eocd + 22 > n)
    {
        if (err)
        {
            *err = "找不到 zip 中央目录结束标记: " + path;
        }
        return false;
    }
    // 中央目录结束记录: +8 本盘条目数, +10 总条目数, +12 目录大小, +16 目录起始偏移
    const std::uint32_t count = Le16(blob_.data() + static_cast<std::ptrdiff_t>(eocd + 10));
    const std::uint32_t cdSize = Le32(blob_.data() + static_cast<std::ptrdiff_t>(eocd + 12));
    const std::uint32_t cdOffset = Le32(blob_.data() + static_cast<std::ptrdiff_t>(eocd + 16));
    if (count == 0xFFFFu || cdSize == 0xFFFFFFFFu || cdOffset == 0xFFFFFFFFu)
    {
        if (err)
        {
            *err = "权重档需要 zip64, 本读取器不支持: " + path;
        }
        return false;
    }
    if (static_cast<std::size_t>(cdOffset) + cdSize > n)
    {
        if (err)
        {
            *err = "中央目录越界: " + path;
        }
        return false;
    }

    std::size_t p = cdOffset;
    for (std::uint32_t i = 0; i < count; ++i)
    {
        if (p + 46 > n || !IsSignature(blob_.data() + p, "PK\x01\x02"))
        {
            if (err)
            {
                *err = "中央目录条目损坏: " + path;
            }
            return false;
        }
        const std::uint16_t method = Le16(blob_.data() + static_cast<std::ptrdiff_t>(p + 10));
        const std::uint32_t compSize = Le32(blob_.data() + static_cast<std::ptrdiff_t>(p + 20));
        const std::uint32_t uncompSize = Le32(blob_.data() + static_cast<std::ptrdiff_t>(p + 24));
        const std::uint16_t nameLen = Le16(blob_.data() + static_cast<std::ptrdiff_t>(p + 28));
        const std::uint16_t extraLen = Le16(blob_.data() + static_cast<std::ptrdiff_t>(p + 30));
        const std::uint16_t commentLen = Le16(blob_.data() + static_cast<std::ptrdiff_t>(p + 32));
        const std::uint32_t localOffset = Le32(blob_.data() + static_cast<std::ptrdiff_t>(p + 42));
        if (p + 46 + nameLen > n)
        {
            if (err)
            {
                *err = "条目名越界: " + path;
            }
            return false;
        }
        ZipEntry e;
        e.name.assign(blob_.data() + static_cast<std::ptrdiff_t>(p + 46), nameLen);
        e.method = method;
        e.compSize = compSize;
        e.uncompSize = uncompSize;

        // 本地文件头的名字与扩展字段长度可能不同于中央目录, 以本地头为准
        if (static_cast<std::size_t>(localOffset) + 30 > n ||
            !IsSignature(blob_.data() + localOffset, "PK\x03\x04"))
        {
            if (err)
            {
                *err = "本地文件头损坏: " + e.name;
            }
            return false;
        }
        const std::uint16_t localNameLen = Le16(blob_.data() + localOffset + 26);
        const std::uint16_t localExtraLen = Le16(blob_.data() + localOffset + 28);
        // TorchScript 用数据描述子写本地头, 其中 crc/长度为 0, 故尺寸一律取中央目录的值
        e.dataOffset = localOffset + 30 + localNameLen + localExtraLen;
        entries_.push_back(std::move(e));

        p += 46 + nameLen + extraLen + commentLen;
    }
    return true;
}

const ZipEntry *ZipReader::Find(const std::string &name) const
{
    for (const ZipEntry &e : entries_)
    {
        if (e.name == name)
        {
            return &e;
        }
    }
    return nullptr;
}

const std::uint8_t *ZipReader::StoredData(const ZipEntry &entry, std::uint64_t *size,
                                          std::string *err) const
{
    if (entry.method != 0)
    {
        if (err)
        {
            *err = "条目 " + entry.name + " 不是 STORED, 本读取器不解压";
        }
        return nullptr;
    }
    if (entry.dataOffset + entry.compSize > blob_.size())
    {
        if (err)
        {
            *err = "条目 " + entry.name + " 数据越界";
        }
        return nullptr;
    }
    if (entry.compSize != entry.uncompSize)
    {
        if (err)
        {
            *err = "条目 " + entry.name + " STORED 但压缩前后长度不一致";
        }
        return nullptr;
    }
    if (size != nullptr)
    {
        *size = entry.compSize;
    }
    return reinterpret_cast<const std::uint8_t *>(blob_.data()) + entry.dataOffset;
}

bool ParseManifest(const std::string &text, std::string *root, std::vector<ParamSpec> *params,
                   std::string *err)
{
    std::string dtype;
    std::string byteOrder;
    std::string gateOrder;
    if (!ExtractStringValue(text, "root", root))
    {
        if (err)
        {
            *err = "清单缺少 root";
        }
        return false;
    }
    if (ExtractStringValue(text, "dtype", &dtype) && dtype != "float32")
    {
        if (err)
        {
            *err = "清单权重类型不是 float32: " + dtype;
        }
        return false;
    }
    if (ExtractStringValue(text, "byteOrder", &byteOrder) && byteOrder != "little")
    {
        if (err)
        {
            *err = "清单字节序不是 little: " + byteOrder;
        }
        return false;
    }
    // 门块顺序写错会让 logits 偏到千分位以上 (实测最大偏差 ~1.9e3, argmax 错 85 处),
    // 所以清单侧一旦改顺序必须在这里挡住, 与 Go 侧 weights.go 同一条检查。
    if (ExtractStringValue(text, "gateOrder", &gateOrder) && !gateOrder.empty() &&
        gateOrder.find("i (input gate), f (forget gate)") == std::string::npos)
    {
        if (err)
        {
            *err = "清单 gateOrder 与本工程前向实现不符: " + gateOrder;
        }
        return false;
    }
    const std::size_t arr = FindKeyValueStart(text, 0, "parameters");
    if (arr == std::string::npos)
    {
        if (err)
        {
            *err = "清单缺少 parameters";
        }
        return false;
    }
    // 逐个取出数组里的 {...} 对象 (对象内不含嵌套对象)
    std::size_t i = arr;
    while (i < text.size())
    {
        const std::size_t open = text.find('{', i);
        if (open == std::string::npos)
        {
            break;
        }
        const std::size_t close = text.find('}', open + 1);
        if (close == std::string::npos)
        {
            if (err)
            {
                *err = "清单 parameters 对象未闭合";
            }
            return false;
        }
        const std::string obj = text.substr(open, close - open + 1);
        ParamSpec spec;
        if (!ExtractIntValue(obj, "entry", &spec.entry) ||
            !ExtractStringValue(obj, "name", &spec.name))
        {
            if (err)
            {
                *err = "清单条目缺少 entry/name";
            }
            return false;
        }
        const std::size_t shapeKey = FindKeyValueStart(obj, 0, "shape");
        if (shapeKey == std::string::npos)
        {
            if (err)
            {
                *err = "清单条目缺少 shape: " + spec.name;
            }
            return false;
        }
        if (ExtractIntArray(obj, shapeKey, &spec.shape) == std::string::npos || spec.shape.empty())
        {
            if (err)
            {
                *err = "清单条目 shape 解析失败: " + spec.name;
            }
            return false;
        }
        params->push_back(std::move(spec));
        i = close + 1;
    }
    if (params->empty())
    {
        if (err)
        {
            *err = "清单 parameters 为空";
        }
        return false;
    }
    return true;
}

} // namespace detail
} // namespace paodekuai
