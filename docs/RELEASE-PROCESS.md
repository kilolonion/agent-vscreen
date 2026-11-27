# 发布流程与安全门禁（Release Process）

发版前，把候选版本、提交 SHA、测试链接、真机记录、沉淀起止时间及 P0/P1 状态写在发版 PR 或对应发布说明里。**未经审核不要推发布标签**；GitHub 的人工批准仍是签名发布的独立门禁。

## 1. 分级沉淀期

| 级别 | 典型变更 | 最低沉淀要求 |
| --- | --- | --- |
| 高风险 | 公网 MCP / OAuth 2.1 / 通道代理、强确认与权限表、Gate / Shizuku / Root、请求签名与认证 | 至少 2 个 beta，且至少 7 天实测无 P0/P1 回归后才能发正式版 |
| 常规 | 新增命令、界面交互、识别/手势优化 | 至少 1 个 beta，且至少 3 天验证后才能发正式版 |
| 紧急安全热修 | 针对已确认漏洞的最小范围修复 | 人工审批可缩短沉淀期，不得跳过 CI、模拟器安全矩阵或发布审批；记录例外理由 |

公网 MCP 是实验性功能：默认关闭，版本更新自动重置。真机回归核对远程发起配对不会自行弹窗，手机锁屏生物识别/PIN 验证、用户主动输入配对码并核对来源后才会出现确认卡；另测严格配对窗口及连续 24 小时无已认证请求自动关闭。

## 2. 候选版本检查清单

1. 更新 `app/build.gradle.kts` 的 `versionCode`、`versionName` 和 `Changes.kt` 顶部的中英 `Release` 条目。运行 `AVS_REGEN=1 ./gradlew --no-daemon :app:testDebugUnitTest`（构建机用 `./build.sh regen`）。确认 `README.md`、`README.zh-CN.md`、`API.md`、`docs/REFERENCE.md` 和 `integrations/operit/dist/packages/agent_vscreen.js` 已同步，二次运行不产生额外 diff，且 `python3 tools/ci/check_aoci_coverage.py` 通过。更新中英 `docs/CHANGELOG*.md` 和非空的 `docs/release-notes/v<version>.md`。
2. 核对候选提交 CI：`android` 的单元测试、regen、AOCI 覆盖率、ABI、debug hook 和 OCR 资产检查，`operit` 打包以及 `secrets` 全历史扫描都须通过。模拟器矩阵含 `agent-e2e`、`root-e2e`、`shizuku`、`gate`、`confirm-attack`、`public-local` 和 `eval`；推 `v*` 标签时 CI 会为该标签提交再跑整套矩阵。
3. 在真机按 [TASKS.md](TASKS.md) 验受影响功能和基础任务，记下版本、设备、日期及通过/失败情况。有 P0/P1、缺测试证据或沉淀期不足时，不批准正式版。
4. 审核以上证据后推 `v<version>`，预发布用带 `-` 后缀的标签。复核待审批 Actions run 的标签、提交及各前置 Job 后再点击批准。发布后对照官方证书与 `SHA256SUMS.txt`，确认 APK 和 Operit ToolPkg 都挂在对应 Release 上。

## 3. GitHub Environment：`release`

在 **Settings → Environments → release**：

- **Required reviewers**：添加维护者账号；每个 beta/正式版签名均须人工批准。关闭 **Allow administrators to bypass configured protection rules**。只有一位维护者时可不启用 **Prevent self-review**，但仍须对该 run 手动审批。
- **Deployment branches and tags**：选 *Selected branches and tags*，只添加 **Tag** 规则 `v*`，不添加 Branch 规则。
- **Environment secrets**：设置 `AVS_KEYSTORE_BASE64`、`AVS_KEYSTORE_PASSWORD`、`AVS_KEY_ALIAS`、`AVS_KEY_PASSWORD`，再删除普通 Repository secrets 中同名的四项。GitHub 不返回已保存的值，迁移必须使用维护者保管的原始 keystore 和配套凭据；不要把它们写进仓库、PR、日志或临时构建产物。

`android` 用 `-PavsUnsignedRelease=true` 产出未签名 APK，不读取签名密钥。`release` 必须等 `android`、`operit`、`secrets` 和同标签模拟器矩阵（`release-device`）通过，并通过 `release` 环境审批。签名 Job 不 checkout、不执行仓库脚本，也不运行 Gradle / Go / pip / Node；只下载该次 run 的 APK、ToolPkg 和说明文件，拒绝已签名输入，用 Runner 预装的 `zipalign`（16 KB 对齐）/ `apksigner` 签名，并核对证书 SHA-256 `56b14824549e698017eba907cbc3347ee3a4ba30907efbd6068ea51ff4495756`。随后生成校验清单并创建 GitHub Release，不覆盖已有发布资产。

已发版本不会因这套流程被重打或改写。不要用旧提交的标签验证迁移；旧发布工作流不认识 Environment，应从经审核的新提交打后续发布标签。
