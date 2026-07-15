# PickTV 1.3.3 来源与隔离基线

状态：M0 来源台账，2026-07-15。

## 本地审计输入

| 输入 | SHA-256 |
|---|---|
| `PickTV-1.3.3.zip` | `7527BBD1E5D1EA24A43FE38BAC2B0712497A8D3D05CE91E525BA8B3E20EEA141` |
| `PickTV-1.3.3.tar.gz` | `883B77F8629FEB21B06DAB6D30B053807FC89D8606C6281BF70C7B2546E1B84F` |
| PickTV `LICENSE.md` | `3972DC9744F6499F0F9B2DBF76696F2AE7AD8AF9B23DDE66D6AF86C9DFB36986` |

压缩包和解压目录位于 Nexora 仓库之外，只用于本地审计与后续行为对照，不允许提交到本仓库。

## 上游声明

PickTV 的 README 声明项目基于 [FongMi/TV](https://github.com/FongMi/TV) 开发，并声明采用 GPL-3.0。Nexora 保留 GPL-3.0 许可证和必要署名。

## M0～M2 导入状态

- PickTV 业务源码：未导入。
- PickTV 资源与品牌素材：未导入。
- PickTV AAR、SO、JAR、APK 等二进制：未导入。
- PickTV 收藏、播放记录、设置、Room 数据库：不迁移。
- 真实用户配置与登录凭据：未导入。
- 兼容性语料：只允许原创合成、无秘密的文本样本。

后续如需导入兼容桥所必需的派生源码，必须在导入前记录原文件路径、上游版本或哈希、许可证、修改说明和替代方案，并在独立 Commit 中完成。
