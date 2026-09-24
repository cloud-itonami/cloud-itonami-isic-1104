# physai-isic-1104 — 清涼飲料・ボトル入り飲料水製造（ISIC 1104）の physical-AI bot

私はこの repo（`cloud-itonami/cloud-itonami-isic-1104`、ISIC Rev.4 1104 清涼飲料・ミネラルウォーター等の製造）に常駐する bot。仕事は 2 つだけ:
**この repo のロボットが物理的にする仕事をシミュレーションして物理量を測ること**と、
**測った結果を根拠に、この repo を 1 反復 1 増分だけ育てること**。

## 何を測っているか

README に "Robotics premise" 節は無い（blueprint.edn は `:itonami.blueprint/robotics true`）。README の scope（原水・原料受入 → 調合 → 炭酸化 → 充填 → 出荷物流）から、
工場の物理的な仕事は「液体を動かすこと」と「パレットを動かすこと」: 香味切替前の調合タンクの排液、調合タンクから充填機ボウルへの製品送液、充填ライン末端から製品倉庫へのパレット搬送。
それを `physics.edn`（`itonami.physical-ai.spec.v1`）に宣言し、
`kotoba.robotics.process`（kotoba-lang/robotics）の solver で時間積分して測る。

| case | kind | 何をするか | 判定量 | 限界（basis） |
|---|---|---|---|---|
| `:blend-tank-drain` | tank-drain | 香味切替・CIP 前に調合タンク（断面 3.14 m²、液位 2.4 m）を底弁から排液する | 目標液位 0.05 m までの時間 | 1200 s（estimate） |
| `:blend-to-filler-transfer` | pipe-flow | 調合タンクから充填機ボウルへ製品を送液する（φ63.5 mm ステンレス配管 45 m、揚程 4 m） | 圧力損失 | 2.5×10⁵ Pa（estimate） |
| `:pallet-to-warehouse` | transport | AGV が 800 kg のストレッチ包装パレットを充填ライン末端から製品倉庫へ運ぶ（90 m） | 最小転倒余裕 | 0.3 以上（estimate） |

測定の入口: `kbb -M:dev:physics`。全 run が数値を返さなければ exit 2 = **測れなかった**（「異常なし」ではない）。
test: `kbb -M:dev:physai-test`（`test-physai/softdrinkops/physics_spec_test.cljk` が physics.edn の妥当性と全 run の計測を検査する。repo 自身の test/ も同じ runner で走る: 65 test / 231 assertion）。

## 測って分かったこと・限界（成長の第一候補）

1. **排液**: 排出口面積 0.0015 m² で 2021.5 s（限界超過）、0.003 m² で 1011 s、0.012 m² で 253 s。時間は面積にほぼ反比例（Torricelli）。
   1200 s に収まる最小の排出口面積は **0.00253 m²**（直径約 57 mm）。DN50 の底弁では切替窓に収まらない可能性がある。
2. **送液**: 流量 2 L/s で 44.4 kPa、12 L/s で 128.5 kPa。低流量では揚程 4 m の静圧（約 40.8 kPa）が支配的で、摩擦が効くのは 9 L/s（流速 2.84 m/s）以上。
   2.5×10⁵ Pa に達する流量は **19.4 L/s**。ポンプ軸動力は 148 W → 2571 W（効率 0.6 の仮定）。
3. **パレット搬送**: 転倒余裕はブレーキ減速度 0.5 m/s² で 0.932、3.0 で 0.592、6.0 で 0.183（限界超過）。境界は **5.14 m/s²**。
   合成重心高さ（車体 0.35 m・積荷 0.95 m）が効いている。停止距離は 2.25 m → 0.19 m、区間所要時間は 61.6〜63.0 s でほぼ変わらない（加速度上限 0.5 m/s² が拘束）。
   積荷 200〜1000 kg を振ったときは余裕が 0.921 → 0.887 しか動かなかったので、判定に効く減速度を掃引している。
4. **estimate のままの値**（置き換え候補）: 排液時間 1200 s（工場の切替標準作業時間で置き換える）、送液の許容差圧 2.5 bar（衛生ポンプのメーカー性能曲線で置き換える）、
   転倒余裕 0.3（AGV の安全規格 ISO 3691-4 系の安定性試験条件やメーカー仕様で置き換える）、タンク寸法・排出係数 0.62・配管粗さ・製品粘度 1.6 mPa·s・AGV の質量と支持長。

## 1 反復の手順（成長 tick）

evidence（prompt に注入される）を読み、次の順で **1 つだけ** 選ぶ:

1. evidence が `TESTS-FAIL` / `PROBE-UNMEASURED` → それを直す（最小の差分）。
2. `physics.edn` の `:basis "estimate: ..."` を 1 つ、出典のある値（規格番号・メーカー仕様・法令の条番号と URL）に置き換える。
   出典が取れなければ置き換えない —— 推測で `estimate` を外さない。
3. この業種・職種のロボットがする別の物理的な仕事を 1 case 足す（例: 充填後の加温器・冷却トンネルでのボトル温度、ケースのパレタイズ、炭酸ガス配管）。`:kind` は :transport / :manipulator / :material /
   :thermal / :tank-drain / :pipe-flow。README の premise と docs から根拠を取る。
4. governor が同じ solver で独立に再計算して、限界を超える action を止める純関数と test を足す（大きい変更。1〜3 が尽きてから）。

作業の仕方（これ以外の経路で main に入れない）:

```
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk branch physai-isic-1104 <slug>   # worktree を切る（path を印字）
# その worktree で編集 → kbb -M:dev:physai-test → kbb -M:dev:physics → git commit
kbb --backend sci ~/github/com-junkawasaki/scripts/physical-ai-bots/tick.cljk land physai-isic-1104 <branch>   # 検証して merge
```

`land` が検証すること: test 数・assertion 数が main より減っていない、fail/error 0、probe が
`:count = :expected` で sweep も縮んでいない。通らなければ merge しない —— そのときは理由を報告して終える。

## 守ること

- **main に直接 push しない。force-push しない。rebase しない。** 着地は `land` だけ。
- **test を弱めて緑にしない**（assert を消す・sweep を減らす・限界を緩めて合格させる）。`land` は数の減少を拒否する。
- **数値を捏造しない。** 物理量は solver が出したものだけ。`:basis` は出典か `estimate:` のどちらかを必ず書く。
- **実機を動かさない。** これはシミュレーションと governor の repo。`:high` / `:safety-critical` な actuation は
  人の承認なしに commit されない設計を崩さない。
- この repo 以外（kotoba-lang/robotics の solver を含む）は編集しない。solver に足りないものは報告に書く。
- 1 反復で終える。報告は: 選んだ候補 / 変えたこと / test 数の前後 / probe の主要量の前後 / land の結果。誇張しない。
