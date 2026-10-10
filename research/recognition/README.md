# 食材识别：检测模型训练与双引擎评测

本目录保存 2026 年 10 月 10 日检测模型重新训练和识别流程评测的脚本、测试图与结果。

## 内容

| 路径 | 说明 |
|---|---|
| `prepare_oid.py` | 从 Open Images V7 的 validation、test 划分取 10 类食材，转换为 YOLO 格式并按 8∶1∶1 划分 |
| `extend_train.py` | 从 train 划分标注文件的前一段补充训练图片 |
| `train.py` | YOLOv8s 微调并在验证集、测试集上评估 |
| `pipeline.py` | 依次执行标注下载、数据补充和训练 |
| `eval_recognition.py` | 在带标注的测试图上评测单引擎、原合并规则和现行编排规则 |
| `testset_basic/` | 基础集：32 张合成测试图与清单 |
| `testset_hard/` | 高难度集：24 张合成测试图与清单 |
| `results/` | 数据集统计、训练曲线、训练摘要、两组评测结果 |

测试图由生成式图像模型按清单中的食材、场景、光照和摆放方式合成，逐张核对后以清单作为标注。

## 结果

检测模型（YOLOv8s，30 轮，训练 4,401 张、验证 109 张、测试 123 张）：验证集 mAP@0.5 为 70.7%，测试集为 54.4%。

类别召回率：

| 测试集 | 单 YOLOv8s | 单 Qwen3-VL-Plus | 原合并规则 | 现行编排 | 云端不可用时的现行编排 |
|---|---|---|---|---|---|
| 基础集（83 个图片—类别对） | 79.5% | 100% | 100%，多 5 个误识别条目 | 100%，无误识别 | 79.5% |
| 高难度集（155 个） | 46.5% | 100% | 100% | 100% | 46.5% |

现行编排见 `app/services/recognition_fusion.py`：云端模型返回结果时以它的类别和数量为准，云端不可用或没有结果时由本地检测模型接管。

## 复现

```
python prepare_oid.py <工作目录>
python extend_train.py <工作目录> 3600
python train.py <工作目录> 30
python eval_recognition.py <best.pt> testset_basic testset_basic/manifest.csv results/recognition_eval_basic.json <env.txt>
```

`env.txt` 第 1 行为 DashScope 密钥，第 4 行为接口地址，不随仓库提交。权重文件 `best.pt` 不入库，见本仓库 Release。
