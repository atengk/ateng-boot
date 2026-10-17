package io.github.atengk.boot.core.validation;

import jakarta.validation.Valid;

import java.io.Serial;
import java.io.Serializable;
import java.util.*;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Predicate;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

/**
 * 支持 JSR-303 级联校验的轻量级泛型 List 包装容器
 * 彻底解决 Spring MVC 中 Controller 接收 {@code @RequestBody @Valid List<DTO>} 时默认忽略列表元素内字段校验的缺陷
 * <p>
 * 使用示例：
 * <pre>{@code
 * @PostMapping("/batch")
 * public Result<Void> batchCreate(@RequestBody @Valid ValidList<UserCreateDTO> list) {
 *     userService.batchCreate(list);
 *     return Result.success();
 * }
 * }</pre>
 *
 * @param <E> 元素泛型类型
 * @author Ateng
 * @since 2026-10-17
 */
public class ValidList<E> implements List<E>, Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    @Valid
    private final List<E> list;

    public ValidList() {
        this.list = new ArrayList<>();
    }

    public ValidList(Collection<? extends E> collection) {
        this.list = collection != null ? new ArrayList<>(collection) : new ArrayList<>();
    }

    public static <E> ValidList<E> of(Collection<? extends E> collection) {
        return new ValidList<>(collection);
    }

    @SafeVarargs
    public static <E> ValidList<E> of(E... elements) {
        if (elements == null || elements.length == 0) {
            return new ValidList<>();
        }
        ValidList<E> validList = new ValidList<>();
        Collections.addAll(validList, elements);
        return validList;
    }

    @Valid
    public List<E> getList() {
        return list;
    }

    @Override
    public int size() {
        return list.size();
    }

    @Override
    public boolean isEmpty() {
        return list.isEmpty();
    }

    @Override
    public boolean contains(Object o) {
        return list.contains(o);
    }

    @Override
    public Iterator<E> iterator() {
        return list.iterator();
    }

    @Override
    public Object[] toArray() {
        return list.toArray();
    }

    @Override
    public <T> T[] toArray(T[] a) {
        return list.toArray(a);
    }

    @Override
    public <T> T[] toArray(IntFunction<T[]> generator) {
        return list.toArray(generator);
    }

    @Override
    public boolean add(E e) {
        return list.add(e);
    }

    @Override
    public boolean remove(Object o) {
        return list.remove(o);
    }

    @Override
    public boolean containsAll(Collection<?> c) {
        return list.containsAll(c);
    }

    @Override
    public boolean addAll(Collection<? extends E> c) {
        return list.addAll(c);
    }

    @Override
    public boolean addAll(int index, Collection<? extends E> c) {
        return list.addAll(index, c);
    }

    @Override
    public boolean removeAll(Collection<?> c) {
        return list.removeAll(c);
    }

    @Override
    public boolean retainAll(Collection<?> c) {
        return list.retainAll(c);
    }

    @Override
    public void replaceAll(UnaryOperator<E> operator) {
        list.replaceAll(operator);
    }

    @Override
    public void sort(Comparator<? super E> c) {
        list.sort(c);
    }

    @Override
    public void clear() {
        list.clear();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ValidList<?> validList = (ValidList<?>) o;
        return Objects.equals(list, validList.list);
    }

    @Override
    public int hashCode() {
        return Objects.hash(list);
    }

    @Override
    public E get(int index) {
        return list.get(index);
    }

    @Override
    public E set(int index, E element) {
        return list.set(index, element);
    }

    @Override
    public void add(int index, E element) {
        list.add(index, element);
    }

    @Override
    public E remove(int index) {
        return list.remove(index);
    }

    @Override
    public int indexOf(Object o) {
        return list.indexOf(o);
    }

    @Override
    public int lastIndexOf(Object o) {
        return list.lastIndexOf(o);
    }

    @Override
    public ListIterator<E> listIterator() {
        return list.listIterator();
    }

    @Override
    public ListIterator<E> listIterator(int index) {
        return list.listIterator(index);
    }

    @Override
    public List<E> subList(int fromIndex, int toIndex) {
        return list.subList(fromIndex, toIndex);
    }

    @Override
    public Spliterator<E> spliterator() {
        return list.spliterator();
    }

    @Override
    public boolean removeIf(Predicate<? super E> filter) {
        return list.removeIf(filter);
    }

    @Override
    public Stream<E> stream() {
        return list.stream();
    }

    @Override
    public Stream<E> parallelStream() {
        return list.parallelStream();
    }

    @Override
    public void forEach(Consumer<? super E> action) {
        list.forEach(action);
    }

    @Override
    public String toString() {
        return list.toString();
    }
}
