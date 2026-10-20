# 网易云歌曲解析来源

- 上游项目：<https://github.com/jiuhunwl/short_videos>
- 上游实现：`api/wyy.py`（歌曲信息与 `url_v1` EAPI/AES 请求流程）
- 适配端：`app/src/main/java/com/probiotics/xiaoni/NeteaseSongResolver.java`
- 许可证：见本目录 `NETEASE-LICENSE`（MIT，Copyright 2025 jiuhunwl）

Android 适配保留上游的无登录流程，仅在网易云官方接口实际返回媒体 URL 时才提供播放/下载；不会拼接 CDN 地址、绕过登录或付费权限。上游的 Flask `/Song_V1` 服务不是客户端内置可直接调用的 API；需部署运行，仓库内示例的 `api.bugpk.com` 端点本次探测也未返回其文档约定的 JSON。汽水分享页明确只返回试听区间时，Dsu 会按试听片段标注，不声称获得全曲。
