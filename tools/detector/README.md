# 无字元素检测器（textless element detector）

找出屏幕上能点、但 OCR 读不出名字的目标（图标、×、开关、图形按钮），给 agent 一个可以直接点的位置。

| 文件 | 作用 |
|---|---|
| `labels.py` | **唯一的标注规则**：无障碍树 → `targets`（无字可点）/ `controls`（有字可点）/ `ignore`。RICO、`uiautomator dump`（模拟器、真机）都走它，不认识任何具体应用。 |
| `prepare.py` | 原始数据 → 统一样本（letterbox 到固定画布 + `samples.jsonl`，按应用包名分训练/验证）。 |
| `to_yolo.py` | 样本 → YOLO 数据集（一类 `icon` = 可点元素）。不引用任何 YOLO 库。 |
| `yolo_eval.py` | 用 agent 关心的指标评估 ONNX：无字目标命中率（检测框中心落在目标里）、落在可点元素上的比例、体积、单线程延迟。 |
| `train.py` | MIT 对照模型（MobileNetV3 + CenterNet，自写训练/导出），同一指标。 |
| `train_colab.ipynb` | Colab 一键训练：RICO → 手机模型 `student`（YOLO11n）+ `teacher`（OmniParser icon_detect 微调）→ fp16 存储 ONNX + `manifest.json`（sha256）。 |

**许可证**：仓库里这些脚本是 MIT。训练出的 YOLO 权重继承 Ultralytics / OmniParser icon_detect，是 **AGPL-3.0**，单独发布、App 按需下载，不进 APK。

**导出格式**：输入 `[1,3,H,W]` 0..1 RGB（左上角 letterbox，灰 114 填充），输出 `[1,5,N]` = cx, cy, w, h（输入像素）, score；NMS 在手机上做。权重 fp16 存储、fp32 计算（`tools/ocr-bench/mkstore16.py`，和 OCR 一样，避开 arm64 fp16 MaxPool 的坑）。

**已知硬伤**：标注来自无障碍树，游戏/画布界面没有无障碍树，训练里几乎没有这类画面，那里能不能用没有证据；RICO 是 2017 年前后的界面。
