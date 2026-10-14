package MTSSynthesis.controller.differential;

import java.util.AbstractMap;
import java.util.AbstractSet;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.ConcurrentModificationException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** Exact state-index storage for the dedicated differential-synthesis path. */
public final class DenseStateStorage {
    private DenseStateStorage() { }

    /**
     * Mutable rows keyed by ERROR=-1, then 0, 1, ... in insertion order.
     * Existing keys may be replaced; skipped new keys are rejected. The caller
     * owns this builder and must publish only an unmodifiable view after use.
     */
    public static <V> Map<Long, V> newAppendOnlyLongMap() {
        return new DenseLongMap<>(null);
    }

    /** Fresh mutable map for declared states; arbitrary domains use HashMap. */
    @SuppressWarnings("unchecked")
    public static <S, V> Map<S, V> mutableMapFor(Set<S> states) {
        Domain domain = denseDomain(Objects.requireNonNull(states, "states"));
        return domain == null ? new HashMap<>()
                : (Map<S, V>) (Map<?, V>) new DenseLongMap<V>(domain);
    }

    /** Copy the outer map, sharing values but never the mutable outer array. */
    public static <S, V> Map<S, V> copyMapFor(Set<S> states, Map<S, V> original) {
        Map<S, V> copied = mutableMapFor(states);
        copied.putAll(Objects.requireNonNull(original, "original"));
        return copied;
    }

    /** Immutable owned state set; dense Long ranges retain no boxed keys. */
    @SuppressWarnings("unchecked")
    public static <S> Set<S> immutableStateCopy(Set<S> states) {
        Objects.requireNonNull(states, "states");
        Domain domain = denseDomain(states);
        if (domain != null) {
            return (Set<S>) (Set<?>) new DenseLongSet(domain);
        }
        Set<S> copy = new HashSet<>();
        for (S state : states) {
            copy.add(Objects.requireNonNull(state, "state"));
        }
        return Collections.unmodifiableSet(copy);
    }

    /** Immutable union, recognizing a contiguous extension before any copying. */
    @SuppressWarnings("unchecked")
    public static <S> Set<S> unionStates(Set<S> original, Set<S> additions) {
        Objects.requireNonNull(original, "original");
        Objects.requireNonNull(additions, "additions");
        Domain old = denseDomain(original);
        if (old != null) {
            boolean hasError = old.first == -1 && old.size != 0;
            long maximum = old.size == 0 ? -1 : old.first + (long) old.size - 1;
            long size = old.size;
            boolean eligible = true;
            for (S state : additions) {
                Objects.requireNonNull(state, "state");
                if (!(state instanceof Long) || (Long) state < -1) {
                    eligible = false;
                    break;
                }
                long value = (Long) state;
                hasError |= value == -1;
                maximum = Math.max(maximum, value);
                if (!original.contains(state)) {
                    size++;
                }
            }
            if (eligible && size <= Integer.MAX_VALUE
                    && maximum == size - 1 - (hasError ? 1 : 0)) {
                return (Set<S>) (Set<?>) new DenseLongSet(
                        new Domain(hasError ? -1 : 0, (int) size));
            }
        }
        Set<S> combined = new HashSet<>();
        for (S state : original) {
            combined.add(Objects.requireNonNull(state, "state"));
        }
        for (S state : additions) {
            combined.add(Objects.requireNonNull(state, "state"));
        }
        return Collections.unmodifiableSet(combined);
    }

    private static Domain denseDomain(Set<?> states) {
        if (states instanceof DenseLongSet) {
            return ((DenseLongSet) states).domain;
        }
        boolean hasError = false;
        long maximum = -1;
        for (Object state : states) {
            Objects.requireNonNull(state, "state");
            if (!(state instanceof Long) || (Long) state < -1) {
                return null;
            }
            long value = (Long) state;
            hasError |= value == -1;
            maximum = Math.max(maximum, value);
        }
        // Set uniqueness plus this cardinality/range check proves no holes.
        return maximum == (long) states.size() - 1 - (hasError ? 1 : 0)
                ? new Domain(hasError ? -1 : 0, states.size()) : null;
    }

    private static final class Domain {
        final int first;
        final int size;

        Domain(int first, int size) {
            this.first = first;
            this.size = size;
        }

        boolean contains(Object key) {
            if (!(key instanceof Long)) {
                return false;
            }
            long value = (Long) key;
            // Test in long before subtraction/casting, including Long.MAX_VALUE.
            return value >= first && value < (long) first + size;
        }
    }

    private static final class DenseLongSet extends AbstractSet<Long> {
        final Domain domain;

        DenseLongSet(Domain domain) { this.domain = domain; }
        public int size() { return domain.size; }
        public boolean contains(Object key) { return domain.contains(key); }
        public Iterator<Long> iterator() {
            return new Iterator<Long>() {
                private int next;
                public boolean hasNext() { return next < domain.size; }
                public Long next() {
                    if (!hasNext()) {
                        throw new NoSuchElementException();
                    }
                    return (long) domain.first + next++;
                }
            };
        }
        public int hashCode() {
            long ordinary = domain.size - (domain.first == -1 ? 1L : 0L);
            // Long.hashCode(-1L) is zero; all ordinary keys fit signed int.
            return (int) (ordinary * (ordinary - 1) / 2);
        }
        public boolean add(Long value) { throw new UnsupportedOperationException(); }
        public boolean remove(Object value) { throw new UnsupportedOperationException(); }
        public boolean addAll(Collection<? extends Long> values) { throw new UnsupportedOperationException(); }
        public boolean removeAll(Collection<?> values) { throw new UnsupportedOperationException(); }
        public boolean retainAll(Collection<?> values) { throw new UnsupportedOperationException(); }
        public boolean removeIf(Predicate<? super Long> predicate) { throw new UnsupportedOperationException(); }
        public void clear() { throw new UnsupportedOperationException(); }
    }

    private static final class DenseLongMap<V> extends AbstractMap<Long, V> {
        private static final Object NULL_VALUE = new Object();
        private final Domain fixedDomain;
        private final int first;
        private Object[] values;
        private int limit;
        private int size;
        private int modifications;

        DenseLongMap(Domain fixedDomain) {
            this.fixedDomain = fixedDomain;
            first = fixedDomain == null ? -1 : fixedDomain.first;
            limit = fixedDomain == null ? 0 : fixedDomain.size;
            values = new Object[fixedDomain == null ? 16 : fixedDomain.size];
        }

        public int size() { return size; }

        private int index(Object key) {
            if (!(key instanceof Long)) {
                return -1;
            }
            long value = (Long) key;
            return value >= first && value < (long) first + limit
                    ? (int) (value - first) : -1;
        }

        @SuppressWarnings("unchecked")
        private V unpack(Object value) { return value == NULL_VALUE ? null : (V) value; }

        public boolean containsKey(Object key) {
            int index = index(key);
            return index >= 0 && values[index] != null;
        }

        public V get(Object key) {
            int index = index(key);
            return index < 0 ? null : unpack(values[index]);
        }

        public V put(Long key, V value) {
            Objects.requireNonNull(key, "state");
            int index = index(key);
            if (index < 0) {
                if (fixedDomain != null || key != (long) first + limit) {
                    throw new IllegalArgumentException("State is outside the declared dense domain: " + key);
                }
                if (limit == Integer.MAX_VALUE) {
                    throw new IllegalStateException("Too many dense states.");
                }
                index = limit++;
                if (index == values.length) {
                    int capacity = (int) Math.min(Integer.MAX_VALUE,
                            Math.max((long) limit, values.length + (long) values.length / 2 + 1));
                    values = Arrays.copyOf(values, capacity);
                }
            }
            Object previous = values[index];
            values[index] = value == null ? NULL_VALUE : value;
            if (previous == null) {
                size++;
                modifications++;
            }
            return unpack(previous);
        }

        public V remove(Object key) {
            int index = index(key);
            if (index < 0 || values[index] == null) {
                return null;
            }
            Object previous = values[index];
            values[index] = null;
            size--;
            modifications++;
            return unpack(previous);
        }

        public void clear() {
            if (size != 0) {
                Arrays.fill(values, null);
                size = 0;
                modifications++;
            }
        }

        private abstract class RowIterator<T> implements Iterator<T> {
            private int expected = modifications;
            private int cursor;
            private int last = -1;

            private void check() {
                if (expected != modifications) {
                    throw new ConcurrentModificationException();
                }
            }

            public boolean hasNext() {
                check();
                while (cursor < limit && values[cursor] == null) {
                    cursor++;
                }
                return cursor < limit;
            }

            int nextIndex() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                last = cursor++;
                return last;
            }

            public void remove() {
                check();
                if (last < 0) {
                    throw new IllegalStateException();
                }
                DenseLongMap.this.remove((long) first + last);
                expected = modifications;
                last = -1;
            }
        }

        public Set<Long> keySet() {
            return new AbstractSet<Long>() {
                public int size() { return DenseLongMap.this.size; }
                public boolean contains(Object key) { return containsKey(key); }
                public void clear() { DenseLongMap.this.clear(); }
                public Iterator<Long> iterator() {
                    return new RowIterator<Long>() {
                        public Long next() { return (long) first + nextIndex(); }
                    };
                }
            };
        }

        public Set<Entry<Long, V>> entrySet() {
            return new AbstractSet<Entry<Long, V>>() {
                public int size() { return DenseLongMap.this.size; }
                public void clear() { DenseLongMap.this.clear(); }
                public Iterator<Entry<Long, V>> iterator() {
                    return new RowIterator<Entry<Long, V>>() {
                        public Entry<Long, V> next() {
                            final int index = nextIndex();
                            return new Entry<Long, V>() {
                                public Long getKey() { return (long) first + index; }
                                public V getValue() { return unpack(values[index]); }
                                public V setValue(V value) { return put(getKey(), value); }
                                public boolean equals(Object other) {
                                    if (!(other instanceof Entry)) {
                                        return false;
                                    }
                                    Entry<?, ?> entry = (Entry<?, ?>) other;
                                    return Objects.equals(getKey(), entry.getKey())
                                            && Objects.equals(getValue(), entry.getValue());
                                }
                                public int hashCode() {
                                    return Objects.hashCode(getKey()) ^ Objects.hashCode(getValue());
                                }
                            };
                        }
                    };
                }
            };
        }
    }
}
