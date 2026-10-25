# paodekuai_server — FakeLua 网页服务端

真人 (座位 0) 对阵两个策略网络 AI: 首出指定、AI 提示、AI 思考面板、记牌器、
战报日志、按会话隔离。规则与推理全部在 C++ 引擎 (../../cpp, 与 Java/Go 逐位
对齐) 里; Lua 只负责 HTTP、会话与流程。

## 构建

先安装 [fakelua](https://github.com/esrrhs/fakelua):

```bash
git clone https://github.com/esrrhs/fakelua
cd fakelua
cmake -S . -B build -DCMAKE_BUILD_TYPE=Release
cmake --build build --target fakelua flua
sudo cmake --install build --prefix /usr/local
```

再编宿主 (CMake 会自动从 `../../cpp` 引入引擎源码):

```bash
cd server
cmake -S host -B host/build -DCMAKE_BUILD_TYPE=Release -DFAKELUA_PREFIX=/usr/local
cmake --build host/build -j
```

## 运行

必须从 `server/` 目录启动 (权重靠 `Policy::DefaultModelDir()` 向上找
`data/models/ts`):

```bash
PAODEKUAI_PORT=8080 ./host/build/paodekuai_server lua     # http://localhost:8080
```

自检 (权重加载 + 三网络 AI 自战一整局 + 会话层与编码契约):

```bash
PAODEKUAI_SMOKE=1 ./host/build/paodekuai_server lua --once
```

HTTP 契约回归:

```bash
python3 tests/api_test.py                                 # 自己拉起服务
python3 tests/api_test.py --base-url http://127.0.0.1:8080  # 打已运行的服务
```

契约见 [API.md](API.md)。

## 生产部署 (gq.esrrhs.xyz)

与 doudizhu/majiang 相同的模式:

```bash
# 1) 同步代码(生产机连不上 GitHub, 用 rsync 推过去)
rsync -az --delete --exclude .git --exclude "cpp/build" --exclude "server/host/build" \
    --exclude "java/target" --exclude ".github" \
    ./ ubuntu@gq.esrrhs.xyz:project/paodekuai_ai/

# 2) 远程构建 + 烟测
ssh ubuntu@gq.esrrhs.xyz
cd project/paodekuai_ai/server
cmake -S host -B host/build -DCMAKE_BUILD_TYPE=Release -DFAKELUA_PREFIX=/usr/local
cmake --build host/build -j
PAODEKUAI_SMOKE=1 ./host/build/paodekuai_server lua --once

# 3) systemd 常驻 (PAODEKUAI_PORT=8891, 已配 /etc/systemd/system/paodekuai.service)
sudo systemctl enable --now paodekuai

# 4) nginx: paodekuai.esrrhs.xyz 的 80/443 server 块 (webroot 签证书,
#    443 反代 127.0.0.1:8891), 见 /etc/nginx/nginx.conf

# 5) 证书续期由 certbot.timer 自动跑; certbot.service 已加 drop-in
#    清掉 /etc/environment 里的代理变量
```

线上地址: <https://paodekuai.esrrhs.xyz>
