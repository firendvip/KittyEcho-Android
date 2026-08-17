# 合成 PCM fixture

`synthetic-tone.wav` 是脚本生成的 0.1 秒、440 Hz、低振幅非语音正弦波，只用于验证 WAV/PCM 合同，不代表任何 ASR 质量。

重新生成：

```bash
python3 scripts/generate_synthetic_fixture.py
```

真实录音、reference、模型输出和逐句结果不得放入此目录。
