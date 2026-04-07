# 查询改造指南：从实体分页 + 手动 convert 迁移到 ExecuteWithSelectService 动态 VO API

## 一、背景

当前很多 Service 代码的写法是：

1. 用 `ExecuteWithSelectService<T, T>` 或 `Mapper/BaseMapper` 查出实体列表/分页；
2. 手动判空；
3. 使用 `MapstructUtils.convert(entityList, XxxVo.class)` 手动转换成 VO；
4. 再 `forEach` 给 VO 填充额外字段。

这样的模式在每个接口里都要重复一套代码，很难统一风格。  
本次增强后，`ExecuteWithSelectService` 已经提供了一套统一的 VO 查询 API（包括分页、列表、单个、按 ID/ID 列表查询），可以大幅减少重复代码。

---

## 二、典型示例改造（推荐参考）

以一个分页示例为例：

### 1. 原始写法（需改造）

```java
// 1) payTimeFilter 部分（保留）
if (payTimeFilter != null) {
    if (payTimeFilter.isEmpty()) {
        Page<PayableOrderVo> empty = new Page<>(pageQuery.getPageNum(), pageQuery.getPageSize(), 0);
        return TableDataInfo.build(empty);
    }
    lqw.in(Order::getId, payTimeFilter);
}

// 2) 查询实体分页
ExecuteWithSelectService<Order, Order> exec = orderMapper.with();
appendSellerFilter(exec, bo);
Page<Order> page = exec.selectPage(pageQuery.build(), lqw);

// 3) 手动判空 + 手动转 VO
if (page.getRecords() == null || page.getRecords().isEmpty()) {
    Page<PayableOrderVo> empty = new Page<>(page.getCurrent(), page.getSize(), page.getTotal());
    return TableDataInfo.build(empty);
}
List<PayableOrderVo> voList = MapstructUtils.convert(page.getRecords(), PayableOrderVo.class);

// 4) 如有，foreach 填充额外字段
for (PayableOrderVo vo : voList) {
    // vo.setXXX(...)
}
```

### 2. 改造后写法（推荐）

```java
// 1) payTimeFilter 部分原样保留（避免 IN 空集合）
if (payTimeFilter != null) {
    if (payTimeFilter.isEmpty()) {
        Page<PayableOrderVo> empty = new Page<>(pageQuery.getPageNum(), pageQuery.getPageSize(), 0);
        return TableDataInfo.build(empty);
    }
    lqw.in(Order::getId, payTimeFilter);
}

// 2) with + 条件配置
ExecuteWithSelectService<Order, Order> exec = orderMapper.with();
appendSellerFilter(exec, bo);

// 3) 直接分页查询 + 关联预加载 + 转 VO
Page<PayableOrderVo> pageParam =
    new Page<>(pageQuery.getPageNum(), pageQuery.getPageSize());

Page<PayableOrderVo> page =
    exec.selectVoPage(pageParam, lqw, PayableOrderVo.class);

// 4) ❗如果原来有 foreach 设置额外字段，这里仍然要保留
for (PayableOrderVo vo : page.getRecords()) {
    // 继续做你原来的手动赋值逻辑
    // vo.setXXX(...)
}

return TableDataInfo.build(page);
```

要点：

- `exec.selectVoPage(...)` 内部已经完成：分页查询实体 + 当前 `with()/hasWhere()` 关联预加载 + `MapstructUtils.convert` 转为 VO；
- 不需要再手动判空和 `convert`；
- **但如果原来有 `voList.forEach(...)` 手动设置额外字段，这段逻辑必须保留，只是数据源变成 `page.getRecords()`。**

---

## 三、通用改造规则（遇到 XX 就改成 XXX）

下面是给团队使用的“一眼看懂”改造对照表。

### 1. 分页查询：`selectPage + convert` → `selectVoPage`

**旧代码模式：**

```java
ExecuteWithSelectService<Entity, Entity> exec = mapper.with();
Page<Entity> page = exec.selectPage(pageParam, wrapper);

List<Vo> voList = MapstructUtils.convert(page.getRecords(), Vo.class);
// 可能还有 voList.forEach(...)
```

**新写法：**

```java
ExecuteWithSelectService<Entity, Entity> exec = mapper.with();
Page<Vo> pageParam = new Page<>(pageNum, pageSize);

Page<Vo> page = exec.selectVoPage(pageParam, wrapper, Vo.class);

// 如有自定义字段，继续 foreach
for (Vo vo : page.getRecords()) {
    // vo.setXXX(...)
}
```

**规则：**

- 遇到「`selectPage(...) -> Page<Entity> -> MapstructUtils.convert(page.getRecords(), Vo.class)`」，统一改成 `selectVoPage(Page<Vo>, wrapper, Vo.class)`；
- 原来的 `voList.forEach` 必须保留，改为对 `page.getRecords()` foreach。

---

### 2. 列表查询：`selectList + convert` → `selectVoList`

**旧代码模式：**

```java
ExecuteWithSelectService<Entity, Entity> exec = mapper.with();
List<Entity> list = exec.selectList(wrapper);
List<Vo> voList = MapstructUtils.convert(list, Vo.class);

// 可能还有 voList.forEach(...)
```

**新写法：**

```java
ExecuteWithSelectService<Entity, Entity> exec = mapper.with();
List<Vo> voList = exec.selectVoList(wrapper, Vo.class);

// 如有自定义字段，继续 foreach
for (Vo vo : voList) {
    // vo.setXXX(...)
}
```

**规则：**

- 遇到「`selectList(...) -> List<Entity> -> MapstructUtils.convert(list, Vo.class)`」，改成 `selectVoList(wrapper, Vo.class)`；
- 原有的 foreach 继续保留，对新的 `voList` 遍历即可。

---

### 3. 单条查询：`selectOne + convert` → `selectVoOne`

**旧代码模式：**

```java
ExecuteWithSelectService<Entity, Entity> exec = mapper.with();
Entity entity = exec.selectOne(wrapper);
Vo vo = MapstructUtils.convert(entity, Vo.class);
```

**新写法：**

```java
ExecuteWithSelectService<Entity, Entity> exec = mapper.with();
Vo vo = exec.selectVoOne(wrapper, Vo.class);
```

**规则：**

- 遇到「`selectOne(...) -> Entity -> MapstructUtils.convert(..., Vo.class)`」，改成 `selectVoOne(wrapper, Vo.class)`。

---

### 4. 按 ID 查询：`selectById + convert` → `selectVoById`

**旧代码模式：**

```java
Entity entity = mapper.selectById(id);
Vo vo = MapstructUtils.convert(entity, Vo.class);
```

或者：

```java
ExecuteWithSelectService<Entity, Entity> exec = mapper.with();
Entity entity = exec.selectById(id);
Vo vo = MapstructUtils.convert(entity, Vo.class);
```

**新写法：**

```java
// 需要 with 关联就走 with()
ExecuteWithSelectService<Entity, Entity> exec = mapper.with();
Vo vo = exec.selectVoById(id, Vo.class);
// 或 mapper.with().selectVoById(id, Vo.class);
```

**规则：**

- 遇到「`selectById(id)` 后接 `convert(..., Vo.class)`」，改成 `with().selectVoById(id, Vo.class)` 或 `exec.selectVoById(id, Vo.class)`；
- 优点：会自动走当前的 `with()/hasWhere()` 配置，统一关联预加载。

---

### 5. 按 ID 列表查询：`selectBatchIds + convert` → `selectVoByIds`

**旧代码模式：**

```java
List<Entity> list = mapper.selectBatchIds(idList);
List<Vo> voList = MapstructUtils.convert(list, Vo.class);
```

**新写法：**

```java
List<Vo> voList =
    mapper.with().selectVoByIds(idList, Vo.class);
// 或先配置 with()/hasWhere() 再 exec.selectVoByIds(...)
```

**规则：**

- 遇到「`selectBatchIds(ids)` 或 `where in idList` 后手动 convert」，优先改成 `selectVoByIds(idList, Vo.class)`；
- 内部自动拼主键 `where in`，自动按当前 `with()/hasWhere()` 关联预加载。

---

### 6. 特别提醒：有 foreach 赋值，一定要保留

**强制记忆点：**

- 所有改造只是为了替换「实体查询 + MapstructUtils.convert」，**不会**自动替你执行 `forEach` 里的业务逻辑；
- 如果原来有类似代码：

  ```java
  List<Vo> voList = MapstructUtils.convert(...);
  for (Vo vo : voList) {
      vo.setExtraField(...);
      // 其他计算逻辑
  }
  ```

- 改造后必须写成：

  ```java
  List<Vo> voList = exec.selectVoList(..., Vo.class);
  for (Vo vo : voList) {
      vo.setExtraField(...);
      // 同样的逻辑继续保留
  }
  ```

**一句话：**  
“查询 + 转换”交给框架，“具体业务字段怎么算”还是你自己的逻辑，该 foreach 的地方一个都不能少。

