package com.xd.xdminiclaw.agent.memory;

import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.checkpoint.BaseCheckpointSaver;
import com.alibaba.cloud.ai.graph.checkpoint.Checkpoint;
import com.alibaba.cloud.ai.graph.checkpoint.savers.MemorySaver;
import com.esotericsoftware.kryo.Kryo;
import com.esotericsoftware.kryo.Serializer;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;
import com.esotericsoftware.kryo.util.Pool;
import lombok.extern.slf4j.Slf4j;
import org.objenesis.strategy.StdInstantiatorStrategy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.*;

/**
 * 基于 Kryo 的文件持久化 CheckpointSaver
 *
 * 继承 MemorySaver，重写 4 个 protected 钩子方法：
 *   - loadedCheckpoints : 首次访问某 thread 时，从文件加载到内存
 *   - insertedCheckpoint: 新增 checkpoint 后，将该 thread 的全量数据刷盘
 *   - updatedCheckpoint : 更新 checkpoint 后，同样刷盘
 *   - releasedCheckpoints: thread 释放后，删除对应文件
 */
@Slf4j
public class KryoFileSaver extends MemorySaver {

    private static final Pool<Kryo> KRYO_POOL = new Pool<>(true, false, 8) {
        @Override
        protected Kryo create() {
            Kryo kryo = new Kryo();
            kryo.setRegistrationRequired(false);
            // 处理无无参构造函数的类
            kryo.setInstantiatorStrategy(new StdInstantiatorStrategy());
            // 注册所有 JDK 不可变/只读集合类型的自定义序列化器
            registerImmutableCollections(kryo);
            return kryo;
        }
    };

    private final Path memoryDir;

    public KryoFileSaver(String memoryDirPath) {
        this.memoryDir = Path.of(memoryDirPath.trim());
        try {
            Files.createDirectories(this.memoryDir);
            log.info("[KryoFileSaver] 会话记忆目录: {}", this.memoryDir.toAbsolutePath());
        } catch (IOException e) {
            throw new IllegalStateException("无法创建会话记忆目录: " + memoryDirPath, e);
        }
    }

    // ─────────────────────────────────────────────────────────
    // MemorySaver 钩子
    // ─────────────────────────────────────────────────────────

    @Override
    protected LinkedList<Checkpoint> loadedCheckpoints(
            RunnableConfig config, LinkedList<Checkpoint> checkpoints) throws Exception {
        if (!checkpoints.isEmpty()) return checkpoints;
        Path file = fileOf(config);
        if (!Files.exists(file)) return checkpoints;
        LinkedList<Checkpoint> loaded = deserialize(file);
        checkpoints.addAll(loaded);
        log.debug("[KryoFileSaver] 从文件加载会话 thread={}, checkpoints={}",
                threadId(config), loaded.size());
        return checkpoints;
    }

    @Override
    protected void insertedCheckpoint(
            RunnableConfig config, LinkedList<Checkpoint> checkpoints, Checkpoint checkpoint) throws Exception {
        flush(config, checkpoints);
    }

    @Override
    protected void updatedCheckpoint(
            RunnableConfig config, LinkedList<Checkpoint> checkpoints, Checkpoint checkpoint) throws Exception {
        flush(config, checkpoints);
    }

    @Override
    protected void releasedCheckpoints(
            RunnableConfig config, LinkedList<Checkpoint> checkpoints, Tag releaseTag) throws Exception {
        Files.deleteIfExists(fileOf(config));
        log.debug("[KryoFileSaver] 已删除会话文件 thread={}", threadId(config));
    }

    // ─────────────────────────────────────────────────────────
    // 序列化 / 反序列化
    // ─────────────────────────────────────────────────────────

    private void flush(RunnableConfig config, LinkedList<Checkpoint> checkpoints) throws Exception {
        Path file = fileOf(config);
        Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
        serialize(tmp, checkpoints);
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        log.debug("[KryoFileSaver] 已持久化会话 thread={}, checkpoints={}",
                threadId(config), checkpoints.size());
    }

    @SuppressWarnings("unchecked")
    private LinkedList<Checkpoint> deserialize(Path file) throws Exception {
        Kryo kryo = KRYO_POOL.obtain();
        try (Input input = new Input(Files.newInputStream(file))) {
            return kryo.readObject(input, LinkedList.class);
        } catch (Exception e) {
            log.warn("[KryoFileSaver] 反序列化失败，已忽略损坏的记忆文件: {}", file, e);
            Files.deleteIfExists(file);
            return new LinkedList<>();
        } finally {
            KRYO_POOL.free(kryo);
        }
    }

    private void serialize(Path file, LinkedList<Checkpoint> checkpoints) throws Exception {
        Kryo kryo = KRYO_POOL.obtain();
        try (Output output = new Output(Files.newOutputStream(file))) {
            kryo.writeObject(output, checkpoints);
        } finally {
            KRYO_POOL.free(kryo);
        }
    }

    // ─────────────────────────────────────────────────────────
    // 工具方法
    // ─────────────────────────────────────────────────────────

    private Path fileOf(RunnableConfig config) {
        String safeName = threadId(config).replaceAll("[^a-zA-Z0-9_\\-]", "_");
        return memoryDir.resolve(safeName + ".kryo");
    }

    private static String threadId(RunnableConfig config) {
        return config.threadId()
                .filter(id -> !id.isEmpty())
                .orElse(BaseCheckpointSaver.THREAD_ID_DEFAULT);
    }

    // ─────────────────────────────────────────────────────────
    // JDK 不可变/只读集合序列化器注册
    // ─────────────────────────────────────────────────────────

    /**
     * 注册所有 JDK 内部不可变集合类型的序列化器。
     *
     * 问题根源：Kryo 默认通过 StdInstantiatorStrategy 绕过构造函数创建对象实例，
     * 然后调用 put/add 填充数据。但 Collections.UnmodifiableMap 等不可变集合
     * 不允许 put，导致 UnsupportedOperationException。
     *
     * 解决方案：为这些类型注册自定义序列化器：
     *   写：正常迭代条目输出
     *   读：写入可变集合，最后包装成原始不可变类型
     */
    private static void registerImmutableCollections(Kryo kryo) {
        // ── Collections.UnmodifiableMap ──────────────────────────
        kryo.register(
                Collections.unmodifiableMap(new HashMap<>()).getClass(),
                new MapSerializer<>(map -> Collections.unmodifiableMap(map)));

        kryo.register(
                Collections.unmodifiableSortedMap(new TreeMap<>()).getClass(),
                new MapSerializer<>(map -> Collections.unmodifiableSortedMap(new TreeMap<>(map))));

        // ── Collections.UnmodifiableList ─────────────────────────
        kryo.register(
                Collections.unmodifiableList(new ArrayList<>()).getClass(),
                new ListSerializer<>(list -> Collections.unmodifiableList(list)));

        // LinkedList 版的 UnmodifiableList 返回 UnmodifiableList（同类）
        kryo.register(
                Collections.unmodifiableList(new LinkedList<>()).getClass(),
                new ListSerializer<>(list -> Collections.unmodifiableList(list)));

        // ── Collections.UnmodifiableSet ──────────────────────────
        kryo.register(
                Collections.unmodifiableSet(new HashSet<>()).getClass(),
                new SetSerializer<>(set -> Collections.unmodifiableSet(set)));

        kryo.register(
                Collections.unmodifiableSortedSet(new TreeSet<>()).getClass(),
                new SetSerializer<>(set -> Collections.unmodifiableSortedSet(new TreeSet<>(set))));

        // ── Collections.EmptyMap / EmptyList / EmptySet ──────────
        kryo.register(Collections.emptyMap().getClass(), new Serializer<Map<?, ?>>() {
            @Override public void write(Kryo k, Output o, Map<?, ?> m) {}
            @Override public Map<?, ?> read(Kryo k, Input i, Class<? extends Map<?, ?>> t) { return Collections.emptyMap(); }
        });
        kryo.register(Collections.emptyList().getClass(), new Serializer<List<?>>() {
            @Override public void write(Kryo k, Output o, List<?> l) {}
            @Override public List<?> read(Kryo k, Input i, Class<? extends List<?>> t) { return Collections.emptyList(); }
        });
        kryo.register(Collections.emptySet().getClass(), new Serializer<Set<?>>() {
            @Override public void write(Kryo k, Output o, Set<?> s) {}
            @Override public Set<?> read(Kryo k, Input i, Class<? extends Set<?>> t) { return Collections.emptySet(); }
        });

        // ── Collections.SingletonMap / SingletonList / SingletonSet ──
        kryo.register(Collections.singletonMap(null, null).getClass(), new Serializer<Map<?, ?>>() {
            @Override public void write(Kryo k, Output o, Map<?, ?> m) {
                Map.Entry<?, ?> e = m.entrySet().iterator().next();
                k.writeClassAndObject(o, e.getKey());
                k.writeClassAndObject(o, e.getValue());
            }
            @Override public Map<?, ?> read(Kryo k, Input i, Class<? extends Map<?, ?>> t) {
                return Collections.singletonMap(k.readClassAndObject(i), k.readClassAndObject(i));
            }
        });
        kryo.register(Collections.singletonList(null).getClass(), new Serializer<List<?>>() {
            @Override public void write(Kryo k, Output o, List<?> l) { k.writeClassAndObject(o, l.get(0)); }
            @Override public List<?> read(Kryo k, Input i, Class<? extends List<?>> t) {
                return Collections.singletonList(k.readClassAndObject(i));
            }
        });
        kryo.register(Collections.singleton(null).getClass(), new Serializer<Set<?>>() {
            @Override public void write(Kryo k, Output o, Set<?> s) { k.writeClassAndObject(o, s.iterator().next()); }
            @Override public Set<?> read(Kryo k, Input i, Class<? extends Set<?>> t) {
                return Collections.singleton(k.readClassAndObject(i));
            }
        });

        // ── Java 9+ List.of / Map.of / Set.of（ImmutableCollections）──
        registerJava9ImmutableCollections(kryo);
    }

    /** 注册 Java 9+ ImmutableCollections 内部类 */
    private static void registerJava9ImmutableCollections(Kryo kryo) {
        // List.of()
        tryRegister(kryo, List.of().getClass(), new ListSerializer<>(list -> List.copyOf(list)));
        tryRegister(kryo, List.of(1).getClass(), new ListSerializer<>(list -> List.copyOf(list)));
        tryRegister(kryo, List.of(1, 2).getClass(), new ListSerializer<>(list -> List.copyOf(list)));
        tryRegister(kryo, List.of(1, 2, 3).getClass(), new ListSerializer<>(list -> List.copyOf(list)));

        // Set.of()
        tryRegister(kryo, Set.of().getClass(), new SetSerializer<>(set -> Set.copyOf(set)));
        tryRegister(kryo, Set.of(1).getClass(), new SetSerializer<>(set -> Set.copyOf(set)));
        tryRegister(kryo, Set.of(1, 2).getClass(), new SetSerializer<>(set -> Set.copyOf(set)));

        // Map.of()
        tryRegister(kryo, Map.of().getClass(), new MapSerializer<>(map -> Map.copyOf(map)));
        tryRegister(kryo, Map.of(1, 1).getClass(), new MapSerializer<>(map -> Map.copyOf(map)));
        tryRegister(kryo, Map.of(1, 1, 2, 2).getClass(), new MapSerializer<>(map -> Map.copyOf(map)));
    }

    /** 忽略重复注册（不同 of() 调用可能返回同一内部类） */
    private static void tryRegister(Kryo kryo, Class<?> type, Serializer<?> serializer) {
        try {
            if (kryo.getRegistration(type) == null) {
                kryo.register(type, serializer);
            }
        } catch (Exception ignored) {
            kryo.register(type, serializer);
        }
    }

    // ── 通用集合序列化器（函数式包装）─────────────────────────

    @FunctionalInterface
    private interface CollectionWrapper<C> {
        C wrap(C mutable);
    }

    private static class MapSerializer<M extends Map<Object, Object>> extends Serializer<M> {
        private final CollectionWrapper<Map<Object, Object>> wrapper;
        MapSerializer(CollectionWrapper<Map<Object, Object>> wrapper) { this.wrapper = wrapper; }

        @Override
        public void write(Kryo kryo, Output output, M map) {
            output.writeVarInt(map.size(), true);
            for (Map.Entry<?, ?> e : map.entrySet()) {
                kryo.writeClassAndObject(output, e.getKey());
                kryo.writeClassAndObject(output, e.getValue());
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        public M read(Kryo kryo, Input input, Class<? extends M> type) {
            int size = input.readVarInt(true);
            Map<Object, Object> map = new LinkedHashMap<>(size);
            for (int i = 0; i < size; i++) {
                map.put(kryo.readClassAndObject(input), kryo.readClassAndObject(input));
            }
            return (M) wrapper.wrap(map);
        }
    }

    private static class ListSerializer<L extends List<Object>> extends Serializer<L> {
        private final CollectionWrapper<List<Object>> wrapper;
        ListSerializer(CollectionWrapper<List<Object>> wrapper) { this.wrapper = wrapper; }

        @Override
        public void write(Kryo kryo, Output output, L list) {
            output.writeVarInt(list.size(), true);
            for (Object item : list) kryo.writeClassAndObject(output, item);
        }

        @Override
        @SuppressWarnings("unchecked")
        public L read(Kryo kryo, Input input, Class<? extends L> type) {
            int size = input.readVarInt(true);
            List<Object> list = new ArrayList<>(size);
            for (int i = 0; i < size; i++) list.add(kryo.readClassAndObject(input));
            return (L) wrapper.wrap(list);
        }
    }

    private static class SetSerializer<S extends Set<Object>> extends Serializer<S> {
        private final CollectionWrapper<Set<Object>> wrapper;
        SetSerializer(CollectionWrapper<Set<Object>> wrapper) { this.wrapper = wrapper; }

        @Override
        public void write(Kryo kryo, Output output, S set) {
            output.writeVarInt(set.size(), true);
            for (Object item : set) kryo.writeClassAndObject(output, item);
        }

        @Override
        @SuppressWarnings("unchecked")
        public S read(Kryo kryo, Input input, Class<? extends S> type) {
            int size = input.readVarInt(true);
            Set<Object> set = new LinkedHashSet<>(size);
            for (int i = 0; i < size; i++) set.add(kryo.readClassAndObject(input));
            return (S) wrapper.wrap(set);
        }
    }
}
