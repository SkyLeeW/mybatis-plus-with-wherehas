# mybatis-plus-with-wherehas 使用说明（Laravel 风格 with/whereHas）

本模块为 MyBatis‑Plus 提供一个“类 Laravel Eloquent 关联查询”的轻量扩展，支持：
- 通过注解在实体上声明关系
- `with()` 预加载关联数据（支持递归层级）
- `hasWhere(...)`（等价 whereHas）对子表先筛选再反向过滤主表
- 关联计数 `count`（子表分组统计）
- 逻辑删除兼容（识别实体上的 `@TableLogic` 并自动过滤）

Maven 坐标：

```xml
<dependency>
    <groupId>io.github.skyleew</groupId>
    <artifactId>mybatis-plus-with-wherehas</artifactId>
    <version>1.0.0</version>
</dependency>
```

核心围绕几个类：注解 `@RelationModel`、工具 `RelationUtil`、查询入口 `ExecuteWithSelectService`、合并逻辑 `HasRelationService`、SQL 执行与字段映射 `RelationSqlService`，以及基础 `BaseHasManyMapper` 扩展。

---

## 快速开始

1) 定义实体与关系（示例：用户拥有多篇文章）

```java
// 子表：文章（posts）
public class Post {
    private Long id;
    @TableField("user_id")
    private Long userId;
    private Integer status;
    // ... 其他字段
}

// 主表：用户（users）
public class User {
    private Long id;

    // 预加载的列表关系：User(id) <-> Post(user_id)
    @RelationModel(field = "id", targetField = "user_id")
    private List<Post> posts;

    // 计数关系：统计每个用户的文章数
    @RelationModel(count = true, table = Post.class, field = "id", targetField = "user_id")
    private Integer postsCount;
}
```

字段含义：
- `field`：主表用于关联的字段（建议写“主表 Java 属性名”，如 `id`）。
- `targetField`：子表用于关联的字段（数据库列名或属性名均可，建议列名，如 `user_id`）。
- `table`：仅在 `count = true` 时必填，用于显式指定子表实体类。
- `count`：是否启用“关联数量统计”。

2) 定义 Mapper（继承 BaseHasManyMapper）

```java
@Mapper
public interface UserMapper extends BaseHasManyMapper<User, UserVO> { }
```

3) 预加载 with 与 whereHas 使用示例

```java
@Autowired UserMapper userMapper;

// 预加载 posts（默认深度 1）
List<User> users = userMapper.with().selectList(new QueryWrapper<User>());

// 指定深度递归预加载（例如还要把 Post 的二级关系也带上）
List<User> deepUsers = userMapper.with(2).selectList(new QueryWrapper<User>());

// whereHas：对子表先过滤，再反向筛主表
List<User> activeUsers = userMapper
    .with()
    .hasWhere(User::getPosts, qw -> qw.eq("status", 1))  // 这里的列名写子表数据库列名
    .selectList(new QueryWrapper<User>());

// 多个 hasWhere：
// - 同一 relationKey 多次调用会叠加子表条件
// - 不同 relationKey 会生成多个 EXISTS 并以 AND 组合
List<User> filteredUsers = userMapper
    .with()
    .hasWhere("posts", qw -> qw.eq("status", 1))
    .hasWhere("posts", qw -> qw.ge("id", 1000))
    .selectList(new QueryWrapper<User>());

// 也支持以字符串 key（实体上 @RelationModel 对应字段名）
List<User> users2 = userMapper
    .with()
    .hasWhere("posts", qw -> qw.ge("id", 1000))
    .selectList(new QueryWrapper<User>());

// VO 转换（第二个泛型为 VO 类型）
List<UserVO> voList = userMapper.with().selectVoList(new QueryWrapper<User>());
 
// 按需排除 with 关联（仅影响预加载，不影响 whereHas）
// 1) 排除根层 posts 关联（等价 User::posts）
List<User> usersNoPosts = userMapper.withExcept("posts").selectList(new QueryWrapper<User>());

// 1.1) 以方法引用排除根层 posts（推荐根层写法）
List<User> usersNoPosts2 = userMapper.withExcept(User::getPosts).selectList(new QueryWrapper<User>());

// 2) 排除嵌套 posts.comments（需要 deep>1 才有意义）
List<User> usersNoComments = userMapper.with(3).exclude("User::posts.comments").selectList(new QueryWrapper<User>());

// 3) 与 whereHas 共存：不加载 posts，但仍可按 posts 条件筛主表
List<User> filtered = userMapper
    .withExcept("posts")
    .hasWhere("posts", qw -> qw.eq("status", 1))
    .selectList(new QueryWrapper<User>());
```

说明：`WhereClosure` 会收到一个 `QueryWrapper<?>`（针对子表），请使用“子表的数据库列名”构造条件（如 `status`、`user_id`）。

---

## 查询方法一览（ExecuteWithSelectService）

> 前提：均通过 `xxxMapper.with()` / `with(int deep)` / `withExcept(...)` / `withOnly(...)` / `hasWhere(...)` 等得到 `ExecuteWithSelectService<T, C>` 实例后调用。

### 实体查询（T）

- `List<T> selectList(Wrapper<T> queryWrapper)`  
  - 功能：按条件查询列表，并根据当前 with 配置预加载关联。
  - 示例：`List<User> list = userMapper.with().selectList(new QueryWrapper<User>().eq("status", 1));`

- `<P extends IPage<T>> P selectPage(P page, Wrapper<T> queryWrapper)`  
  - 功能：按条件分页查询实体列表，自动填充 `page.setRecords(...)`，并预加载关联。
  - 示例：
    ```java
    Page<User> page = new Page<>(pageNum, pageSize);
    Page<User> result = userMapper.with().selectPage(page, lqw);
    ```

- `T selectOne(Wrapper<T> queryWrapper)`  
  - 功能：按条件查询单条实体（内部自动 `limit 1`），并预加载关联。
  - 示例：`User one = userMapper.with().selectOne(new QueryWrapper<User>().eq("id", 1L));`

- `T selectById(Serializable id)`  
  - 功能：根据主键查询单个实体，自动按主键列构造条件，并预加载关联。
  - 示例：`User one = userMapper.with().selectById(userId);`

- `List<T> selectByIds(Collection<? extends Serializable> ids)`  
  - 功能：根据主键集合查询实体列表（内部自动构造 `where pk in (...)`），并预加载关联。
  - 示例：`List<User> list = userMapper.with().selectByIds(idList);`

### 绑定 VO 查询（Mapper 第二个泛型为 VO，即 C）

- `C selectVoOne(Wrapper<T> queryWrapper)`  
  - 功能：按条件查询单条实体并转换为 Mapper 绑定的 VO 类型 C。
  - 示例：`UserVO one = userMapper.with().selectVoOne(new QueryWrapper<User>().eq("id", 1L));`

- `<P extends IPage<C>> P selectVoPage(P page, Wrapper<T> queryWrapper)`  
  - 功能：按条件分页查询实体并转换为 VO，`page` 的泛型与记录类型均为 C。
  - 示例：
    ```java
    Page<UserVO> page = new Page<>(pageNum, pageSize);
    Page<UserVO> result = userMapper.with().selectVoPage(page, lqw);
    ```

- `List<C> selectVoList(Wrapper<T> queryWrapper)`  
  - 功能：按条件查询实体列表并转换为 VO 列表。
  - 示例：`List<UserVO> list = userMapper.with().selectVoList(new QueryWrapper<User>().eq("status", 1));`

- `C selectVoById(Serializable id)`  
  - 功能：根据主键查询单个实体并转换为 VO。
  - 示例：`UserVO one = userMapper.with().selectVoById(userId);`

- `List<C> selectVoByIds(Collection<? extends Serializable> ids)`  
  - 功能：根据主键集合查询实体列表并转换为 VO 列表。
  - 示例：`List<UserVO> list = userMapper.with().selectVoByIds(idList);`

### 动态 VO 查询（一个实体多种 VO）

> 适用于 `Mapper` 第二个泛型仍然写实体（如 `BaseHasManyMapper<Order, Order>`），但在 Service 中希望动态指定 VO 类型的场景。

- `<V> V selectVoOne(Wrapper<T> queryWrapper, Class<V> voClass)`  
  - 示例：`PayableOrderVo one = orderMapper.with().selectVoOne(lqw, PayableOrderVo.class);`

- `<V, P extends IPage<V>> P selectVoPage(P page, Wrapper<T> queryWrapper, Class<V> voClass)`  
  - 示例：
    ```java
    Page<PayableOrderVo> page = new Page<>(pageNum, pageSize);
    Page<PayableOrderVo> result =
        orderMapper.with().selectVoPage(page, lqw, PayableOrderVo.class);
    ```

- `<V> List<V> selectVoList(Wrapper<T> queryWrapper, Class<V> voClass)`  
  - 示例：`List<OrderExportVo> list = orderMapper.with().selectVoList(lqw, OrderExportVo.class);`

- `<V> V selectVoById(Serializable id, Class<V> voClass)`  
  - 示例：`PayableOrderVo one = orderMapper.with().selectVoById(orderId, PayableOrderVo.class);`

- `<V> List<V> selectVoByIds(Collection<? extends Serializable> ids, Class<V> voClass)`  
  - 示例：`List<PayableOrderVo> list = orderMapper.with().selectVoByIds(orderIds, PayableOrderVo.class);`

---

## 核心概念与执行流程

- 注解扫描与关系元数据构建：`RelationUtil.checkRelation` 会扫描实体上带 `@RelationModel` 的字段，构建 `RelationModelStructure`：
  - 解析主表/子表 `TableInfo`（MP 元数据）、`relationFieldKey`（参与关联的列名）、`relationField`（主表的 Java 成员属性）等。
  - `targetField` 未显式指定时，会回退为子表主键列；大多“多对一/一对多”场景都应显式填 `targetField`（如 `user_id`）。
  - 主表侧 `field` 建议填写“Java 属性名”（如 `id`），系统会映射到真实列名。

- 预加载（with）：`ExecuteWithSelectService.selectList` 先执行主表查询，然后通过 `HasRelationService.relationModel`：
  - 收集主表用于关联的 id 集合
  - `RelationSqlService.getResult` 拼接子表 SQL：`select <子表列> from <子表> where <targetField> in (ids)`
- 结果经 `formatV2` 做“列名→属性名”映射（支持 `@TableField`、同名、下划线→驼峰），并尝试对“看起来像 JSON 的字符串”按目标字段类型反序列化
  - 以 `<targetField>` 分组，回填到主表的目标字段中（`List` 或单个对象）

- whereHas：`ExecuteWithSelectService.hasWhere` 将关系字段名映射到一个子表 `QueryWrapper`。
  - `RelationSqlService.getSubSelectResult` 以 EXISTS 模式生成反向筛选子句：
    - `EXISTS (SELECT 1 FROM <子表> rel1 WHERE rel1.<targetField> = <主表>.<field> AND <子表条件>)`
    - 自表与跨表统一适配，避免列名冲突与大集合 IN 性能问题。
  - 列名解析策略（已增强）：优先使用“主表 Java 属性名”解析真实列名；兼容直接传入列名；兜底尝试驼峰→下划线。

### 仅约束预加载的过滤：withWhere（不筛主表）

- 语义：对 with 预加载的子集合追加过滤，不改变主表记录的返回数量。
- API：
  - `withWhere(String relation, WhereClosure child)`
  - `withWhere(SFunction<T, ?> relation, WhereClosure child)`
- 行为：父表全量返回；子集合按条件过滤（不命中则子集合为空）。
- 示例（自表 GoodsCategory）：
  ```java
  mapper.with()
        .withWhere(GoodsCategory::getChildren, qw -> qw.like("category_name", "%手机%"))
        .selectList(new QueryWrapper<>());
  ```
- SQL：在子表查询中拼接 AND 子表条件：
  - `SELECT ... FROM <子表> WHERE <targetField> IN (ids) [AND 未删除] AND (<子表条件>)`

### EXISTS 模式说明与示例（含自表）

- 语义变化：hasWhere 由“IN 子查询”改为“EXISTS 子查询”。
- 优势：
  - 自表查自表无列名冲突：子表固定别名 `rel1`，关联使用 `rel1.<targetField> = <主表表名>.<field>`；
  - 一般对索引更友好，避免大集合 IN 带来的性能与边界问题。
- 使用规范：
  - `WhereClosure` 中字符串列名写“子表列名”（如 `status`、`category_name`）；
  - 自表无需任何额外配置；
  - 参数可用 `QueryWrapper` 的占位写法（内部会内联为字面量再注入 apply）。

示例：自表 GoodsCategory 查询直接子分类条件筛父分类

```java
public class GoodsCategory {
    @TableId("id")
    private Long id;
    @TableField("category_parent_id")
    private Long categoryParentId;

    @TableField(exist = false)
    @RelationModel(field = "id", targetField = "category_parent_id")
    private List<GoodsCategory> children;
}

// 只筛选“拥有名称包含 手机 的直接子分类”的父分类
List<GoodsCategory> res = mapper
  .with()
  .hasWhere("children", qw -> qw.like("category_name", "%手机%"))
  .selectList(new QueryWrapper<>());
```

生成 EXISTS 片段（示意）：

```
EXISTS (
  SELECT 1 FROM goods_category rel1
  WHERE rel1.category_parent_id = goods_category.id
    AND rel1.category_name LIKE '%手机%'
    [AND 逻辑删除未删条件]
)
```

跨表（User 与 Post）：

```
EXISTS (
  SELECT 1 FROM post rel1
  WHERE rel1.user_id = user.id AND rel1.status = 1
)
```

注意：如果你的“主表查询”层引入了自定义别名（而非默认 `表名.列名`），需要同步扩展外层列的别名来源；当前默认使用主表的表名限定列名。

- 计数（count）：当 `@RelationModel(count=true)` 时，走 `RelationSqlService.getCountResult`，SQL 形如：
  - `SELECT <targetField>, COUNT(*) AS relation_count FROM <子表> WHERE <targetField> IN (...) GROUP BY <targetField>`
  - 把计数值回填到主表对应数字字段（`Integer/Long`）。

- 递归深度：`with(int deep)` 控制递归层级（默认 1）。当子表实体自身也声明了 `@RelationModel` 时，可层层预加载。

---

## 按需排除关联（with 排除规则）

- 背景：默认 `with()` 会加载实体上所有带 `@RelationModel` 的字段，有时需要临时禁用部分关联。
- 能力：
  - `withExcept(String... rules)` 或链式 `with().exclude(String... rules)` 按规则屏蔽指定路径的关联。
  - 根层支持方法引用：`withExcept(User::getPosts)` 或 `with().exclude(User::getPosts)`。
  - 以上仅影响 with 预加载，不影响 `hasWhere`。

- 规则语法：
  - 根层简写：`"posts"` 等价于 `"User::posts"`（仅在根层匹配）。
  - 指定 Owner：`"User::posts"`（不区分大小写，使用类 SimpleName）。
  - 嵌套路径：`"User::posts.comments"`（深度>1 时生效）。

- 示例：
  - 排除根层（字符串）：`userMapper.withExcept("posts").selectList(qw);`
  - 排除根层（方法引用）：`userMapper.withExcept(User::getPosts).selectList(qw);`
  - 排除嵌套：`userMapper.with(3).exclude("User::posts.comments").selectList(qw);`
  - 与 whereHas 共存：
    ```java
    userMapper
      .withExcept("posts")        // 不加载 posts 关联
      .hasWhere("posts", qw -> qw.eq("status", 1)) // 仍可按 posts 条件筛主表
      .selectList(qw);
    ```

- 注意：
  - 被排除的字段不会执行关联 SQL 与回填；`count=true` 的统计字段同样被跳过。
  - 规则未命中将被忽略（打印 debug 日志）。
  - 不配置规则时，行为与当前版本保持一致。

---

## 按需包含关联（with 仅加载规则）

- 背景：有时需要“默认都不加载”，只加载指定的少数几个关联。
- 能力：
  - `withOnly(String... rules)` 或链式 `with().only(String... rules)` 仅允许命中的路径被预加载，其余全部跳过。
  - 根层支持方法引用：`withOnly(User::getPosts)` 或 `with().only(User::getPosts)`。
  - 仅影响 with 预加载，不影响 `hasWhere`。

- 规则语法与排除相同（但增强：支持无 Owner 的嵌套路径）：
  - 根层简写：`"posts"` 等价于 `"User::posts"`（仅在根层匹配）。
  - 指定 Owner：`"User::posts"`。
  - 嵌套路径：`"User::posts.comments"` 或 `"posts.comments"`（等价；仅加载到该层）。
  - 通配含义（自动包含所有层级）：在末尾追加 `.*` 或 `.**` 表示“匹配该路径及所有子级”。例如：
    - `"posts.*"` 或 `"posts.**"`：加载 `posts` 以及其所有更深层（需要 `with(depth)` 足够深）。
    - 同样适用于排除：`exclude("posts.*")` 会排除 `posts` 及其所有子级。

- 示例：
  - 仅加载根层 posts：`userMapper.withOnly("posts").selectList(qw);`
  - 仅加载根层（方法引用）：`userMapper.withOnly(User::getPosts).selectList(qw);`
  - 仅加载嵌套 posts.comments：`userMapper.with(3).only("User::posts.comments").selectList(qw);`
  - 与 whereHas 共存：
    ```java
    userMapper
      .withOnly("posts")            // 仅加载 posts 关联
      .hasWhere("posts", qw -> qw.eq("status", 1))
      .selectList(qw);
    ```

- 注意：
  - include 与 exclude 同时链式调用时，以“最后一次配置”为准。
  - 若未命中任何包含规则，则不会加载任何关联（但主表查询仍照常执行）。
  - 如果希望 `addr` 及其全部下级自动加载，使用 `withOnly("addr.*")` 或 `withOnly("addr.**")`；同理可用于 `exclude`。

---

## 规则语法与匹配详解（适用于 include/exclude）

- 基本形式：
  - 根层简写：`"posts"` 仅在根层匹配（无 Owner）。
  - 指定 Owner：`"User::posts"` 在所属实体为 `User` 的根层匹配。
  - 嵌套路径：`"posts.comments"` 可在任意层匹配精确路径（推荐无 Owner 写法用于嵌套）。

- Owner 的语义：
  - 规则中的 Owner 与“当前遍历层的实体类”匹配。
  - 根层的 Owner 就是主实体类，例如 `User`。
  - 递归到下一层后，Owner 会变为子实体类（例如 `Post`）。因此：
    - `"User::posts.comments"` 这类“带 Owner 的嵌套规则”在深层可能不匹配（因 Owner 已变更）。
    - 建议对嵌套路径使用“无 Owner”写法：`"posts.comments"` 或结合通配 `"posts.*"`。

- 通配与前缀：
  - 末尾加 `.*` 或 `.**` 表示匹配“该路径及其所有子级”。示例：
    - `only("posts.*")` 等价于“允许 `posts` 及其所有更深层路径”。
    - `exclude("posts.*")` 等价于“排除 `posts` 及其所有更深层路径”。
  - 通配不会提升递归深度；仍需设置足够的 `with(depth)`。

- 深度与命中：
  - `with(depth)` 仅控制最大递归层数；是否实际预加载由规则匹配决定。
  - 示例：`with(2).withOnly("addr", "addr.children")` 会加载 `addr` 和其 `children`；
    若仅 `withOnly("addr")` 则不会自动包含 `children`，除非写 `"addr.children"` 或 `"addr.*"`。

- 其它说明：
  - 规则大小写不敏感（类名/字段名）。
  - 多条规则去重后生效；未命中的规则会被忽略。
  - 仅影响 with 预加载，不影响 `hasWhere(...)` 的条件拼接。
  - 实体字段名需准确匹配；笔误（如 `chiren` vs `children`）不会命中。

---

## 重要约定与最佳实践

- 列名与属性名：
  - 子表 `QueryWrapper`（`hasWhere`）里请使用子表“数据库列名”（`snake_case`）。
  - 系统在需要主表列名时会用“属性名（驼峰）→列名（下划线）”映射；也兼容传入列名。

- 推荐显式设置 `targetField`：
  - 未设置时默认回退为子表主键列，很多一对多场景会不符合预期。

- `field` 建议写主表 Java 属性名：
  - 如 `id`。系统会找到对应真实列名并使用。

- JSON 字段自动反序列化：
  - 若数据库返回的字符串像 JSON，且目标字段类型是 `List<T>` 或普通对象，`formatV2` 会尝试用 Jackson 反序列化。
  - 失败会保留原值并打印日志。

- VO 转换：
  - 泛型 VO：`selectVoList/selectVoPage/selectVoOne/selectVoById` 使用 `MapstructUtils.convert`，请保证对应 VO 类存在，并在你工程中提供 MapStruct 依赖/实现。
  - 动态 VO（一个实体对应多个 VO）：无需为每个 VO 新建 Mapper，可在 Service 层调用：
    ```java
    // 假设 OrderMapper 仍然是 BaseHasManyMapper<Order, Order>
    ExecuteWithSelectService<Order, Order> exec = orderMapper.with();

    // 1) 根据单个 ID 查询并转成 VO（包含 with 预加载的关联数据）
    PayableOrderVo one = exec.selectVoById(orderId, PayableOrderVo.class);

    // 1.1) 根据 ID 列表查询并转成 VO 列表（内部自动 where in 主键）
    List<PayableOrderVo> list =
        exec.selectVoByIds(orderIdList, PayableOrderVo.class);

    // 2) 分页查询 + 关联 + 转 VO
    Page<PayableOrderVo> pageParam = new Page<>(pageNum, pageSize);
    Page<PayableOrderVo> voPage =
        exec.selectVoPage(pageParam, lqw, PayableOrderVo.class);

    // 3) 列表查询 + 关联 + 转成另一个 VO
    List<OrderExportVo> exportList =
        exec.selectVoList(lqw, OrderExportVo.class);
    ```
  - 动态 VO 对应的方法为：
    - `selectVoOne(Wrapper<T> queryWrapper, Class<V> voClass)`
    - `selectVoPage(P page, Wrapper<T> queryWrapper, Class<V> voClass)`
    - `selectVoList(Wrapper<T> queryWrapper, Class<V> voClass)`
    - `selectVoById(Serializable id, Class<V> voClass)`
    - `selectVoByIds(Collection<? extends Serializable> ids, Class<V> voClass)`

---

## 关键类与路径

- 注解：`src/main/java/org/jieyun/relationmapping/annotation/RelationModel.java`
- Mapper 扩展：`src/main/java/org/jieyun/relationmapping/mapper/BaseHasManyMapper.java`
- 查询入口：`src/main/java/org/jieyun/relationmapping/service/ExecuteWithSelectService.java`
- 关联合并：`src/main/java/org/jieyun/relationmapping/service/HasRelationService.java`
- SQL 与字段映射：`src/main/java/org/jieyun/relationmapping/service/RelationSqlService.java`
- 关系元数据：`src/main/java/org/jieyun/relationmapping/domain/*`
- 注解扫描与默认规则：`src/main/java/org/jieyun/relationmapping/utils/RelationUtil.java`
- 子查询闭包：`src/main/java/org/jieyun/relationmapping/interfaces/WhereClosure.java`
- 原生 SQL Mapper：`src/main/java/org/jieyun/relationmapping/mapper/RawSqlMapper.java`, `src/main/resources/mapper/RawSqlMapper.xml`
- 按需排除过滤器：
  - 接口：`src/main/java/org/jieyun/relationmapping/filter/RelationFilter.java`
  - 规则解析与匹配：`src/main/java/org/jieyun/relationmapping/filter/RelationSelector.java`
  - 规则结构：`src/main/java/org/jieyun/relationmapping/filter/ParsedRule.java`

---

## 变更记录（本次关键增强）

- whereHas 主表列名解析策略：
  - 优先用“主表 Java 属性名”解析真实列名
  - 兼容直接传入列名（snake_case）
  - 兜底尝试驼峰→下划线匹配

对应代码：`src/main/java/org/jieyun/relationmapping/service/RelationSqlService.java:273`, `src/main/java/org/jieyun/relationmapping/service/RelationSqlService.java:429`。

- 新增：按需排除关联（with 排除规则）
  - 新 API：`withExcept(String... rules)`、`with().exclude(String... rules)`
  - 规则例：`"posts"`、`"User::posts"`、`"User::posts.comments"`
  - 仅影响 with 预加载，不影响 `hasWhere`

对应代码：
- 接口与解析：
  - `src/main/java/org/jieyun/relationmapping/filter/RelationFilter.java`
  - `src/main/java/org/jieyun/relationmapping/filter/RelationSelector.java`
  - `src/main/java/org/jieyun/relationmapping/filter/ParsedRule.java`
- 入口集成：
  - `src/main/java/org/jieyun/relationmapping/mapper/BaseHasManyMapper.java`
  - `src/main/java/org/jieyun/relationmapping/service/ExecuteWithSelectService.java`
  - `src/main/java/org/jieyun/relationmapping/service/HasRelationService.java`

---

## 常见问题

- 子查询条件中列名写属性名还是列名？
  - 写“列名”（如 `user_id`）。`QueryWrapper` 的字符串列名语义即数据库列。

- 为什么 with 返回的字段名是驼峰？
  - `formatV2` 会把数据库列名映射为实体的 Java 属性名键（便于直接回填到对象/VO）。

- 没写 `targetField` 为什么查询不对？
  - 默认回退为“子表主键列”，大多数一对多需要的是子表外键（如 `user_id`），请显式设置。

---

## 依赖与环境

- MyBatis‑Plus（实体元数据 `TableInfo`/`TableField`、`QueryWrapper` 等）
- Jackson（JSON 反序列化）
- Hutool（字符串下划线/驼峰转换等）
- 你的工程中应已集成这些依赖（本模块为通用包的一部分）。

---

如需进一步示例（复杂多级关系、分页 VO、条件组合等），可以告诉我你的实体与库表结构，我会补充更贴近你场景的用法。

---

## 逻辑删除支持（@TableLogic）

- 自动识别子表实体上的 `@TableLogic` 字段，并在以下场景追加“未删除”条件：
  - `with()` 关联数据查询
  - `hasWhere(...)` 子查询（用于反向筛主表）
  - 关联计数 `count`
- 多种数据格式兼容：
  - 字符串/字符：如 `'0'/'1'`、`'N'/'Y'`，按需自动加引号
  - 数值：0/1/2 等，作为数值字面量
  - 布尔：true/false（跨库尽量保持兼容）
  - 时间戳/日期：当 `@TableLogic(value = "NULL")` 或字段为时间类型时，按 `IS NULL` 作为“未删除”条件
- 优先级：
  - `@TableLogic(value, delval)` 显式值优先
  - 其次读取全局配置 `mybatis-plus.global-config.db-config.logic-not-delete-value`（本仓库默认未删=0，已删=2）
- 使用方式：
  - 在实体上标注逻辑删除字段，例如：
    ```java
    @TableLogic
    private String delFlag; // 未删=0，已删=2（取全局配置）
    ```
    或者：
    ```java
    @TableLogic(value = "0", delval = "1")
    private Integer deleted;
    ```

---

## 附录：@TableLogic 文档摘要（MyBatis‑Plus）

- 基本概念：
  - 通过在实体字段上标注 `@TableLogic` 实现“软删除”，未真正删除行，仅用一个标记字段识别删除状态。
  - 注解属性含义：`value` 表示“未删除值”，`delval` 表示“已删除值”。若未设置，则采用全局配置。

- 全局配置（可在各业务模块的 `application.yml` 覆盖）：
  ```yaml
  mybatis-plus:
    global-config:
      db-config:
        # 建议与实体注解保持一致；不指定 logic-delete-field 也可，仅靠注解即可生效
        logic-delete-value: 2           # 已删除值（示例）
        logic-not-delete-value: 0       # 未删除值（示例）
  ```

- 实体注解示例：
  ```java
  // 示例1：走全局配置，未删=0，已删=2
  @TableLogic
  private String delFlag;

  // 示例2：显式指定，未删=0，已删=1
  @TableLogic(value = "0", delval = "1")
  private Integer deleted;

  // 示例3：时间戳软删，未删=IS NULL
  @TableLogic(value = "NULL", delval = "now()")
  private LocalDateTime deletedAt;
  ```

- 行为范围：
  - MyBatis‑Plus 内置 CRUD 查询会自动附带“未删除”条件；`deleteById` 等删除方法会转为“更新为已删除值”。
  - 如需在个别 Mapper/方法忽略逻辑删除，可考虑使用 `@InterceptorIgnore(logicDelete = "1")`（仅对标准 MP 流程有效）。

- 与本模块的集成（关联查询扩展）：
  - 我们在以下场景显式追加“未删除”条件（因为执行了原生 SQL）：
    - `with()` 预加载子表数据
    - `hasWhere(...)` 子查询（反向筛主表）
    - 关联计数 `count`
  - 多种数据格式自动兼容：字符串/字符、数值、布尔、日期/时间（日期/时间按 `IS NULL` 判定“未删除”）。

- 常见问题与排错：
  - “java: 找不到符号 LogicDeleteInnerInterceptor”：逻辑删除不需要该拦截器类，官方并无此类；请使用实体 `@TableLogic` 与全局配置即可。
  - 业务中不同模块可覆盖 `logic-delete-value`/`logic-not-delete-value`；请确认取值一致性，避免混淆。
  - 时间戳软删建议结合字段填充（删除时写入当前时间），查询侧按 `IS NULL` 视为未删除。
