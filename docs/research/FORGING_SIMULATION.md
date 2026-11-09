# 锻造形变的技术研究

> 目的：回答一个问题——**能不能做出"体积守恒的立体锻打，但用 2D 像素明暗表现"**。
> 本文只做技术判断，不代表已经拍板的实现方案。
> 状态：初稿，待用户确认方向后再细化。

## 1. 问题拆解

用户提出的要求，其实是三个独立的技术问题：

1. **计算**：锤击改变金属形状，且**总体积守恒**（现在的探针是"用勺子挖"，只减不加）
2. **表现**：形变必须是 3D 的，但最终呈现为符合 MC 画风的 2D 像素图
3. **交互**：实时响应，不能是离线烘焙

这三个问题的成熟度完全不同——1 有大量文献但实时化难，2 有现成思路，3 是工程问题。

## 2. 体积守恒：为什么"挖坑"是错的

金属在常温/锻造温度下**近似不可压缩**。锤子压下去，被排开的材料必须流向别处——
要么向四周挤出（变宽），要么沿长度方向延展（变长）。这就是锻造的基本规律：
**锤击不是减材，是重新分配。**

现在的探针用高斯凹陷 `h -= depth * exp(...)`，只做减法，所以看起来像被勺子挖过。
正确做法是加一个"流动"项，把被压掉的体积按某种规则分配到周围。

### 相关文献

**塑性变形的连续介质模型（准确但重）**

- Bargteil et al., *A Finite Element Method for Animating Large Viscoplastic Flow*, SIGGRAPH 2007
  — 黏塑性流动的 FEM 动画，是"金属被锤变形"最贴近的图形学模型。
- Jiang et al., *The Material Point Method for Simulating Continuum Materials*, SIGGRAPH 2016 course
  — MPM 的系统性教程，适合金属这类"先弹性后塑性"的材料。
- Klár et al., *Drucker-Prager Elastoplasticity for Sand Animation*, SIGGRAPH 2016
  — 塑性屈服准则的实现细节，金属可换用 von Mises。
- Stomakhin et al., *A Material Point Method for Snow Simulation*, SIGGRAPH 2013
  — MPM 的经典入门实现。
- Yue et al., *Continuum Foam: A Material Point Method for Shear-Dependent Flows*, 2015
  — 剪切主导的流动，和金属延展有相似之处。
- 传统金属成形方向：刚塑性有限元（rigid–plastic FEM）中的**体积损失恢复**是长期存在的问题，
  见 *A simple volume loss recovery scheme in non-steady state metal forming analysis*（J. Mater. Process. Technol.）
  — 说明"体积守恒"在工业仿真里也是要专门处理的，不是免费得到的。
- *Numerical and Experimental Investigation of Multi-axial Forging of AA6082 Alloy*（Springer 2024）
  — 多向锻造的实验对照，可用来校准"锤击后材料往哪流"。

**实时形变与体积约束（可落地）**

- Müller et al., *Meshless Deformations Based on Shape Matching*, SIGGRAPH 2005
  — 形状匹配，稳定但默认不保体积。
- Müller et al., *Position Based Dynamics*, 2007
- Macklin et al., *XPBD: Position-Based Simulation of Compliant Constrained Dynamics*, 2016
  — **体积约束**可以作为一个约束项直接加进去，这是实时方案里最实用的一条路。
- Sorkine & Alexa, *As-Rigid-As-Possible Surface Modeling*, 2007
- Botsch & Sorkine, *On Linear Variational Surface Deformation Methods*, 2008
  — 保体积/保细节的变形能量，偏离线编辑。
- Debunne et al., *Dynamic Real-Time Deformations*, SIGGRAPH 2001
  — 早期实时形变，思路仍有参考价值。
- *Physically Based Shape Matching*, CGF 2022
  — 形状匹配的现代复述，含体积修正。

**结论**：真正的塑性 FEM / MPM 在 MC 的实时预算里不现实（单块金属也要几千粒子 + 求解器）。
可行的路线是"**高度场 + 体积再分配**"，用 PBD 式的约束迭代来保证总量不变。

## 3. 表现：3D 计算怎么变成 2D 像素

这是本方案最巧的一环——**"立体只存在计算里"其实是可行的，而且有现成思路**。

- *Voxel2Pixel*（BenMcLean）：把体素模型渲染成像素画资源的库，正是"3D 数据 → 2D 像素"的参考实现。
- *spotvox*（tommyettinger）：类似的体素转像素思路。
- 经典 *Shape from Shading*（Horn, 1970）及其现代变体：从明暗反推形状；
  我们做的是**反向应用**——已知高度场，算法线，再算明暗。
- NVIDIA 的 *Normal Map / Per-Pixel Lighting* 文档：高度图 → 法线 → 光照的标准流程。

也就是说：**不需要真的渲染 3D 模型**。给定高度场 `h(x,z)`，用有限差分求法线，
套一个固定的斜向光源，就能得到每个像素的明暗值，直接写成一张像素贴图。
这既保住了"立体计算"，又保住了 MC 的像素画风。

## 4. 数据结构的选择

| 方案 | 表达力 | 实时性 | 实现成本 | 适用 |
| --- | --- | --- | --- | --- |
| 2.5D 高度场 | 厚度分布、压痕、延展 | 极好 | 低 | 扁平的刀身 |
| 3D 体素 | 真正的立体、可弯折 | 中 | 中 | 需要截面变化时 |
| 四面体 FEM | 最真实 | 差 | 高 | 不现实 |
| MPM | 最真实（可流动） | 差 | 很高 | 不现实 |

**判断**：刀身是扁的，用 2.5D 高度场（厚度场）就够表达"压薄—挤出"。
但高度场表达不了**弯曲**（刀身的中线弯了），所以需要额外一条"中线曲线"参数。

形态 = 轮廓（厚度 > 0 的区域）+ 高度场（厚度分布）+ 中线曲线（弯曲）。

这三者都由锤击改变，且都是低维数据，存档小、可复现。

## 5. 尚待验证的工程风险

1. **动态贴图**：每把刀要有唯一贴图 → 需要运行时生成纹理（`DynamicTexture`）。
   成百上千把刀会撑爆显存，必须有纹理缓存池 + LRU。
2. **物品渲染**：动态纹理无法进 atlas，物品图标需要 `BlockEntityWithoutLevelRenderer`
   （Create 自己也大量使用 BEWLR）。
3. **服务端权威**：形状只在服务端计算，客户端收参数重建，否则多人会各算各的。
4. **性能**：高度场 32×32 = 1024 个点，每次锤击做一次约束迭代，开销可忽略；
   真正的瓶颈在贴图生成和上传。

## 5.5 具体算法：从文献里拆出来的做法

读了 Matthias Müller 的 Ten Minute Physics 软体教程（此人就是 PBD 的作者）和 PBD 原始论文。
**体积约束的核心代码非常短**，总共不到 30 行：

1. 把物体切成很多小四面体
2. 每个四面体记住一个"静止体积"（一开始的体积）
3. 每帧算当前体积，和静止体积的差就是误差
4. 沿"让误差变小"的方向移动四个顶点
5. 重复几遍，误差收敛

翻译成我们的高度场：

1. 把刀身切成小格子，每格记一个厚度
2. 每格有一个"静止厚度"
3. 锤击让某格变薄 → 总厚度减少 → 违反守恒
4. 把减少的量按"谁更厚谁先让"的规则分给邻居
5. 重复几遍，总量回到原值

三步对应那篇教程里的 `solveVolumes`：
- 算每格的"修正方向"（往哪加厚最快）
- 算总修正能力（和权重/质量有关）
- 沿方向修正，直到总量守恒

**塑性怎么来**（这是关键）：PBD 默认是弹性的，松手会弹回去。
要"锤完就定型"，只需在每次锤击后把**静止形状更新成当前形状**。
这样形变变成永久——这正是金属塑性变形的本质：超过屈服点后，材料的记忆被改写。

## 5.6 属性与形状的非线性关系（文献考证）

用户要求："关系肯定不能是线性，具体在现实里怎么工作我也不知道，需要读论文考证。"

### 5.6.1 刃角 → 切割力：非线性的，而且是"越薄越赚，太薄就崩"

工程切割模型里，切割力与刃角的关系大致是：

```
切割力 ∝ sin(θ + τ) / (1 − sin(θ + τ))
```

- `θ`：刃角的一半
- `τ`：刃面与材料之间的摩擦角

这个式子的形状很重要：**分母会随刃角增大而缩小，所以切割力不是线性上升，而是越往后涨得越快**。
反过来，刃角越薄，切割力下降得越快——所以"刃角从 20° 磨到 15°"带来的收益，
远大于"从 30° 磨到 25°"。

来源：工程教材 [Cutting Models (LibreTexts)](https://eng.libretexts.org/@api/deki/pages/29466/pdf/8.3%253A%2bCutting%2bModels.pdf)、
UNSW 切削力论文中的楔形切割公式（`F_C = 2σ_t d · sin(θ+τ)/(1−sin(θ+τ))`）。

### 5.6.2 刃口半径 → 阈值效应

刃角之外还有一个隐藏变量：**刃口半径**（微观上刃口不是一个点，而是一个小圆弧）。

- 刃口越细，初始切入所需的力量越小
- 刃口变钝，会先出现一个"完全切不进去"的门槛，超过门槛才突然切入

这就是"钝刀子砍不动"的物理来源——**不是力不够，是没跨过阈值**。

### 5.6.3 质量 → 能量：平方关系

```
动能 = ½ × 质量 × 速度²
```

但人挥剑的**刀尖速度有上限**（受肌肉与转动惯量限制）。所以：

- 增加质量 → 能量线性增加
- 但同时转动惯量上升 → 速度下降
- 两者抵消，最终伤害增长会**放缓**（收益递减）

### 5.6.4 长度 → 转动惯量：平方关系

```
转动惯量 = Σ (质量 × 到旋转轴距离²)
```

长度增加 10%，转动惯量大约增加 20%。所以**剑越长，攻速下降得比长度增加更快**。
这是"长兵器迟钝"的物理来源。

### 5.6.5 破甲 → 阈值效应

穿透护甲本质上是"局部压强是否超过材料屈服强度"：

- 低于阈值：完全不破防（甚至弹开）
- 高于阈值：穿透
- 中间几乎没有平滑过渡

这正是用户设计"软伤 / 硬伤"的物理依据——**伤害不是连续可加，而是分段生效**。

### 5.6.6 对游戏设计的含义

| 关系 | 建议曲线 | 理由 |
| --- | --- | --- |
| 刃角 → 锋利度 | **反比 + 阈值** | 越薄收益越大，薄过头崩刃 |
| 质量 → 伤害 | **收益递减** | 动能平方但速度受限 |
| 长度 → 攻速 | **平方衰减** | 转动惯量按距离平方 |
| 破甲 → 护甲效果 | **阶跃** | 阈值效应 |

结论：**四项里没有一项是线性的**。这也意味着，只要按物理直觉建模，
"无唯一最优解"几乎是自动成立的——因为每条收益曲线都会在极端处强行回头。

## 5.7 冷作硬化与各向异性（文献验证）

### 5.7.1 冷作硬化（Strain Hardening）

**现象**：金属在冷态下塑性变形后，强度与硬度上升，延展性下降。

**定量描述**用 Hollomon 方程：

```
σ = K · εⁿ
```

- `σ`：流动应力，即"想让金属继续变形需要多大的力"
- `ε`：已经发生的塑性应变量
- `K`：材料常数（强度系数）
- `n`：硬化指数，钢材大约 0.1–0.25

这个式子的意思是：**变形越多，越难继续变形**。n 越大，硬化越明显。

**机制**：塑性变形会让晶体内部的位错密度上升，位错互相缠结、运动受阻，
所以需要更大的应力才能继续变形。

**对游戏的含义**：

- 每次锤击累积"加工量"
- 局部屈服强度随加工量上升 → 同样力度压出的变形越来越小
- 退火（回火）把加工量清零，金属重新变软
- **这自然产生了"锤几下就得回炉"的节奏**

用户的设计（冷锻 → 硬伤↑、耐久↓）与物理一致：强度上升、韧性下降。

### 5.7.2 各向异性（Anisotropy）

**现象**：锻造会改变晶粒的取向分布，形成"织构"（texture），
使材料在不同方向上性能不同：沿变形方向更强，垂直方向更弱。

**证据**：A5052 铝合金多轴锻造实验（J-STAGE, 2020）
- 6 道次、总应变 4.8 后，晶粒细化到 1 μm 以下
- 主织构为 `{011}<100>` 和 `{011}<110>`
- 变形机制可用 Sachs 模型解释

来源：[Change in microstructure and crystal orientation of an A5052 aluminum alloy processed by multi-directional forging](https://www.jstage.jst.go.jp/article/jilm/70/10/70_701002/_article/-char/en)

**对游戏的含义**：

- 每把剑有一个"纹理方向"场，沿主要变形方向延伸
- 该方向上的强度/韧性更高，垂直方向更低
- 折叠锻打会不断重排方向，最终形成交错花纹——这是大马士革的物理基础

实现上可以给每个体素存一个"方向偏好"（2 bit 表示 4 个方向），
或者记录累积变形方向。

## 5.8 大马士革花纹的形成机制（文献考证）

**核心论文**：Verhoeven, Pendray & Dauksch,
*The Key Role of Impurities in Ancient Damascus Steel Blades*,
JOM 50(9), 1998, pp. 58–64.
[全文（开放）](http://dtrinkle.matse.illinois.edu/MatSE584/articles/key_role_impurities/key_role.html)

### 5.8.1 两种"大马士革"

| 类型 | 工艺 | 花纹来源 |
| --- | --- | --- |
| 折叠锻焊（pattern-welded） | 高碳钢与低碳钢交替叠片，反复折叠锻焊 | 层与层之间的明暗对比 |
| 乌兹钢（wootz） | 坩埚熔炼，1.5% 碳，锻打 | 碳化物颗粒成带排列 |

### 5.8.2 花纹不是"画"上去的

论文的关键结论：表面花纹来自**碳化物带（Fe3C 颗粒带）与刀面的交角**。

- 碳化物带平行于锻造平面
- 带的间距 30–70 μm
- 刀面与带平面相交时，交角 `α` 决定表面上的带宽度
- **`α` 变化 → 表面花纹波动**

所以花纹的本质是：**内部结构的截面投影**。改变结构方向，花纹自动变化。

### 5.8.3 梯子纹（ladder pattern）的工艺

论文给出的可靠做法：

1. 刀打得接近完成
2. 在刀面上刻/磨出**横槽**（或锻出凹槽）
3. 继续锻平，金属流动填满凹槽
4. 凹槽底部的金属流动**改变了带的局部角度**
5. 结果：表面带间距发生周期性变化，形成梯子纹

**这直接证明：花纹可以通过"制造局部流动"来定向设计，而不是随机。**

### 5.8.4 锤头形状会影响花纹

论文明确提到：用**圆头锤或锤击锤**（rounded or peening hammer heads）
可以显著增加花纹的波浪程度——因为锤头形状改变了局部 `α` 角。

这直接支撑"锤头种类是核心玩法"的设计。

### 5.8.5 对游戏实现的含义

1. **花纹 = 层的投影，不是贴图**。粒子方案（D-038）天然支持：
   层的方向一变，投影出来的花纹自动变。
2. **玩家控制的是"结构"，不是"图案"**。折叠、扭转、刻槽、锤击角度——
   这些操作改变层的排列，花纹是结果。
3. **锤头形状必须影响花纹**。这是论文验证过的真实工艺，也是工具系统的价值。
4. **刻槽可以成为一个独立操作**（类似錾子），用于制造定向花纹。

### 5.8.6 粒子法与锻造模拟的参考

- *Physics-Informed Preform Design for Flashless 3D Forging via Material Point Backtracking and FEM*
  — MPM 用于锻造预成型设计，说明 MPM 适合金属体积成形。
- *Numerical Modelling of Metal Forming by SPH with Multi-GPU Acceleration*
  — SPH + 多 GPU，粒子量大的现实解法。
- *CRESSim-MPM* — 开源 MPM 库，可参考 P2G / G2P 的实现。

## 6. 我的判断

**技术上可行，但要放弃"真实物理"，换"看起来对"的近似。**

- 计算层：高度场 + 体积约束迭代（PBD 思路），可选加中线弯曲
- 表现层：高度场 → 法线 → 明暗 → 像素贴图
- 参考实现：Voxel2Pixel 的投影思路 + XPBD 的体积约束

这条路能满足"体积守恒"和"像素画风"，代价是**不能真的模拟金属流动的细节**
（比如折叠锻打的花纹、夹钢的分层）。如果以后要做花纹钢，
需要在高度场上再加一层"材料分布场"。

## 5.9 MPM 的具体算法（实现级，已考证）

参考：
- [Wikipedia: Material point method](https://en.wikipedia.org/wiki/Material_point_method) — 算法框架
- [WebGPU MPM Snow Simulation: implementation.md](https://github.com/luoluobuli/WebGPU-MPM-based-Snow-Simulation/blob/main/docs/implementation.md) — P2G/G2P 的完整公式与代码
- Stomakhin et al., *A Material Point Method for Snow Simulation*, SIGGRAPH 2013
- Jiang et al., *The Material Point Method for Simulating Continuum Materials*, SIGGRAPH 2016 course

### 5.9.1 四步循环

```
P2G     粒子把质量与动量分给邻近网格点
Grid    网格上施加外力、求解约束
G2P     网格把更新后的速度还给粒子
Clear   清空网格
```

每次锤击跑 3–5 个子步，子步越少越快，越慢越稳。

### 5.9.2 权重：二次 B 样条

粒子落在格子里的相对位置 `x`（0–1），对相邻三层的权重是：

```
w(-1) = 0.5 × (1 − x)²
w( 0) = 0.75 − (x − 0.5)²
w( 1) = 0.5 × x²
```

三个轴各算一遍，乘起来就是该格子的总权重。**这就是“粒子像投票一样把质量分给 27 个格子”的具体算法。**

### 5.9.3 形变梯度

每个粒子维护一个 3×3 矩阵 `F`，记录“这块材料被拉伸/剪切/旋转了多少”。

```
F ← (I + ∇v · Δt) · F
```

`∇v` 是速度梯度，从周围格子的速度算出来。`F` 是后续一切的关键：
塑性、硬化、花纹方向都从它推。

### 5.9.4 塑性：把 F 拉回合理范围

金属被锤之后应该“定型”，不能弹回去。做法是**把 F 分解，再把过大的部分截断**：

```
F = U · Σ · Vᵀ        （奇异值分解）
Σ' = 把 Σ 的每个奇异值限制在 [min, max]
F' = U · Σ' · Vᵀ      （截断后的形变，成为新的“静止状态”）
```

- 对金属：**体积守恒**，所以要求 `det(Σ') = 1`
- 超过上限的部分被“永久保留”——这就是塑性
- 这就是“锤完就定型”的数学表达

### 5.9.5 硬化

累积塑性应变（每次锤击产生的 `Σ` 变化量）计入一个标量 `εp`：

```
屈服强度 = 基础强度 × (1 + k · εp^n)
```

这就是 Hollomon 方程（见 5.7.1）在粒子系统里的实现。
退火时把 `εp` 清零。

### 5.9.6 我们的参数

| 项 | 值 |
| --- | --- |
| 粒子数 | **10,000**（初始上限，后续下调） |
| 网格 | **32 × 16 × 6**（3,072 格，每格约 3.3 个粒子） |
| 每锤击子步 | 3–5 |

**为什么是"降网格、加粒子"**：

MPM 的精度由粒子决定，网格只负责求解稳定性。
网格细而粒子少 → 粒子稀疏，出现"肥皂泡"式变形；
网格粗而粒子多 → 稳定，细节由粒子承担。

原方案 64×32×6 配 2700 粒子，每格 0.22 个粒子，差一个数量级。
新方案 32×16×6 配 10,000 粒子，每格 3.3 个粒子，进入稳定区间。

**存储**：约 100 KB/把（原始），压缩后目标 30–40 KB。

**调优策略**：先按 10,000 跑，验证稳定性后再逐步下调粒子数，
找"还能看、够快"的最低值。

## 5.10 可行性验证：能锤出形状

目标：验证 MPM 能否锤出「刀背厚、刃口薄」的坡度。

### 参数

| 项 | 值 |
| --- | --- |
| 粒子 | 10,000 |
| 网格 | 32×16×6，范围 [-0.5, 1.5] |
| 本构 | corotated，μ=3，λ=50 |
| 屈服 | 0.12，硬化系数 3 |
| 重力 | 0.5 |
| 钳子 | X < 0.12 固定 |
| 锤头 | 半径 0.04，力度 0.5，倾斜 15° |
| 锤数 | 40（沿 X 一排，Y=0.60） |

### 结果

| 指标 | 数值 | 目标 |
| --- | --- | --- |
| 体积误差 | 2.7% | <5% |
| 厚度梯度 | 0.077 → 0.047（39%） | >30% |
| NaN | 0 | 0 |

### 关键机制

1. **位置投影（全局体积保持）**：只改形变矩阵 F 不够，粒子本身会散开。
   必须直接约束包围盒体积，超了就整体向质心收缩，少了就扩张。
2. **夹持 + 重力**：没有夹持，锤击会把整块材料推走；没有重力，材料会漂浮、边缘翘起。
3. **锤头要小**：半径 0.04（约 2.5 个粒子间距）才能做局部控制。
   半径 0.10 时一整锤覆盖半个刀身，局部控制不了。

### 未解决

- 材料会超出方块范围（渲染与存档范围需要扩大）
- 厚度梯度还不够锐（刃口应该更薄）
- 参数仍是手调的，没有系统性标定
- SPH 对比失败（只有排斥没有内聚，炸成雾），已排除

## 7. 参考文献清单

按主题归类，标星的是建议精读的。

**塑性 / 流动**
- ★ Bargteil et al., FEM for Animating Large Viscoplastic Flow, SIGGRAPH 2007
- ★ Jiang et al., The Material Point Method for Simulating Continuum Materials, SIGGRAPH 2016 course
- Klár et al., Drucker-Prager Elastoplasticity for Sand Animation, SIGGRAPH 2016
- Stomakhin et al., A Material Point Method for Snow Simulation, SIGGRAPH 2013
- Yue et al., Continuum Foam, 2015
- A simple volume loss recovery scheme in non-steady state metal forming analysis, JMPT
- Numerical and Experimental Investigation of Multi-axial Forging of AA6082 Alloy, 2024

**实时形变 / 体积约束**
- ★ Macklin et al., XPBD, 2016
- ★ Müller et al., Meshless Deformations Based on Shape Matching, SIGGRAPH 2005
- Müller et al., Position Based Dynamics, 2007
- Sorkine & Alexa, As-Rigid-As-Possible Surface Modeling, 2007
- Botsch & Sorkine, On Linear Variational Surface Deformation Methods, 2008
- Debunne et al., Dynamic Real-Time Deformations, SIGGRAPH 2001
- Physically Based Shape Matching, CGF 2022
- An Optimized Mass-spring Model with Shape Restoration Ability Based on Volume Conservation

**网格 / 雕刻**
- Interactive Mesh Sculpting with Arbitrary Topologies in HMD Environments
- OmniBody: Discretized Local Constraints for Real-Time Destructible and Flexible Soft Bodies
- Adaptive tetrahedral meshing for real-time sculpting

**3D → 2D 像素**
- ★ Voxel2Pixel (BenMcLean)
- spotvox (tommyettinger)
- Horn, Shape from Shading, 1970
- NVIDIA, Normal Map / Per-Pixel Lighting

**工程参考**
- Minecraft `DynamicTexture` / `BlockEntityWithoutLevelRenderer`
- Create 的 Ponder 场景系统（用于教学演示锻造流程）
