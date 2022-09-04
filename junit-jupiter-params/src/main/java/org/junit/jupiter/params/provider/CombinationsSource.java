/*
 * Copyright © 2022. Mike Duigou
 */

package org.junit.jupiter.params.provider;

import static org.apiguardian.api.API.Status.EXPERIMENTAL;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.Spliterator;
import java.util.function.Consumer;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import java.util.stream.DoubleStream;
import java.util.stream.IntStream;
import java.util.stream.LongStream;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.apiguardian.api.API;

/**
 * Produces a stream of test arguments generated from provided enumerable
 * sources. Each {@link Arguments} instance in the returned stream will contain
 * the same count of arguments as the count of provided sources, however, each
 * instance will contain different values for the arguments. The values are
 * generated by iterating over the values of each source and producing all
 * possible combinations in order.
 * <p>
 * Examples:
 <pre>{@code
 *   Stream<Arguments> testCases() {
 *      return fromSources(LongStream.range(1L, 3L));
 *   }
 * }</pre>
 * <p>
 * Would produce a stream of 2 single argument test cases consisting of boxed
 * {@code long} values;
 * {@code { 1L },} and {@code { 2L }}.
 * <p>
 * Examples:
 * <pre>{@code
 *   Stream<Arguments> testCases() {
 *      return fromSources(boolean.class, boolean.class);
 *   }
 * }</pre>
 * <p>
 * Would produce a stream of four 2-argument test cases consisting of all the
 * combinations of two boolean values;
 * {@code { false, false }, { false, true }, { true, false },} and
 * {@code { true, true }}.
 * <p>
 * Examples:
 * <pre>{@code
 *   private enum AlertLevel { GREEN, YELLOW, RED, PLAID }
 *
 *   Stream<Arguments> testCases() {
 *      return fromSources(AlertLevels.class);
 *   }
 * }</pre>
 * <p>
 * Would produce a stream of four single-argument test cases consisting of all
 * the {@code enum AlertLevel} values;
 * {@code { GREEN }, { YELLOW }, { RED },} and {@code { PLAID}}.
 * <p>
 * <pre>{@code
 *   private enum State { California, Oregon, Washington }
 *   private enum Model { Car, Truck, Bicycle }
 *   private enum Colour { GREEN, YELLOW, RED, BLUE }
 *   Stream<Arguments> testCases() {
 *      return fromSources(State.class, Model.class, Colour.class, Boolean.class, IntStream.range(1, 5));
 *   }
 * }</pre>
 * <p>
 * Would produce a stream of 288 5-argument test cases consisting of all the
 * combinations of {@code State}, {@code Model}, and {@code Colour} enums, a
 * boolean, and the integers 1 through 4.
 *
 * <p>Using {@code fromSources} is intended as a convenient alternative to
 * having to write many
 * {@link MethodSource} implementations similar to:
 * <pre>{@code
 *   Stream<Arguments> testCases() {
 *      List<Arguments> case = new ArrayList();
 *      for(State eachState : State.values()) {
 *          for (Model eachModel : Model.values()) {
 *              for (Colour eachColour : Colour.values()) {
 *                  for (boolean eachBoole : new boolean[] { false, true}) {
 *                      for (int eachInt = 1; eachInt < 5; eachInt++) {
 *                          cases.add(new Arguments(eachState, eachModel, eachColour, eachBoole, eachInt));
 *                      }
 *                  }
 *              }
 *         }
 *     }
 *     return cases.stream();
 *   }
 * }</pre>
 *
 * <p>The behaviour of this source is affected by several optional system properties:
 * 	<dl>
 *		<dt>{@code org.junit.jupiter.params.provider.CombinationsSource.immutable}</dt>
 *		<dd>(default "false") If true then all sources provided are copied
 *		to ensure that the resulting stream
 * 		is immutable.
 * 		</dd>
 *		<dt>{@code org.junit.jupiter.params.provider.CombinationsSource.split}</dt>
 *		<dd>(default "100,000") An integer value that specifies the target
 *		minimum size of a split. If test cases are very slow then you may wish to
 * 		reduce this for additional splitting.
 *		</dd>
 *		<dt>{@code org.junit.jupiter.params.provider.CombinationsSource.parallel}</dt>
 *		<dd>(default "false") A boolean value that controls whether the resulting
 *		stream is {@link Stream#parallel()}. JUnit5 does not currently use parameter
 *		sources in parallel, but there has been an RFE to support parallel sources
 *      </dd>
 *	</dl>
 */
@API(status = EXPERIMENTAL)
public class CombinationsSource {

	/**
	 * if true then all source inputs are copied before stream is returned.
	 * This requires more space but allows any source to be used.
	 */
	private static final boolean SPLITERATOR_IMMUTABLE = Boolean.getBoolean(
		"org.junit.jupiter.params.provider.CombinationsSource.immutable");

	/**
	 * target minimum size of a split.
	 */
	private static final long SPLITERATOR_SPLIT_THRESHOLD = Long.getLong(
		"org.junit.jupiter.params.provider.CombinationsSource.split", 100_000L);

	/**
	 * if true then returned stream will be parallel. JUnit5 does not currently
	 * use parameter sources in parallel, but there has been an RFE to support
	 * parallel sources
	 */
	private static final boolean STREAM_PARALLEL = Boolean.getBoolean(
		"org.junit.jupiter.params.provider.CombinationsSource.parallel");

	/**
	 * A utility method that produces a stream of test arguments from provided 
	 * sources.
	 *
	 * @param sources The sources over which to iterate to produce the
	 * {@link Arguments} values. The order and count of sources matches the
	 * order and count of arguments in the resulting combinations. Each source
	 * may be:
	 * <dl>
	 * 	<dt>{@code null}<dd>The argument value will always be {@code null}.
	 * 	<dt>A {@link Supplier}<dd>Used for wrapping. Will be called only once
	 * 	to produce a single value.
	 * 	<dt>AN {@link Iterable}<dd>Any iterable.
	 * 	<dt>An array<dd>A primitive or reference array.
	 * 	<dt>A {@link Boolean} or {@code boolean} class<dd>Will iterate over
	 * 	the boolean values
	 * 	{@link Boolean#FALSE} and {@link Boolean#TRUE}
	 * 	<dt>An {@link Enum}<dd>Any enum class.
	 * 	<dt>An {@link IntStream}, {@link LongStream}, {@link DoubleStream},
	 * 	or {@link Stream}<dd>Any finite stream.
	 * 	<dt>An {@link Iterator}<dd>Any finite iterator.
	 * 	<dt>An {@link Enumeration}<dd>Any finite enumeration.
	 * </dl>
	 * @return A stream of {@link Arguments} with values derived from the 
	 * provided sources.
	 */
	public static Stream<Arguments> fromSources(Object... sources) {
		return StreamSupport.stream(sourcesSpliterator(sources), STREAM_PARALLEL).map(Arguments::of);
	}

	/**
	 * Produce a spliterator of object arrays from the provided sources. Each
	 * object array contains the same number of objects as there are provided
	 * sources. The spliterator returns all combinations of each source.
	 *
	 * @param sources the sources to be used for producing combinations.
	 * @return spliterator providing all combinations of source objects as
	 * object arrays.
	 */
	static Spliterator<Object[]> sourcesSpliterator(Object... sources) {
		Object[][] converted = convertSources(SPLITERATOR_IMMUTABLE,
			Objects.requireNonNull(sources, "sources is null"));
		return new ArraysSpliterator(SPLITERATOR_IMMUTABLE, converted);
	}

	/**
	 * Converts all the provided sources to fully boxed array instances. This
	 * is needed because, for all but the first source, we need to iterate
	 * over the source multiple times. Materializing the sources also minimizes
	 * the primitive boxing used.
	 *
	 * @param safeCopy if true then resulting arrays will be private copies.
	 * @param sources The sources to materialize in to arrays.
	 * @return an array of {@link Collection} instances which materialize the
	 * sources.
	 */
	@SuppressWarnings("UnnecessaryBoxing")
	static Object[][] convertSources(boolean safeCopy, Object... sources) {
		Object[][] converted = Arrays.stream(sources).map(source -> {
			if (null == source) {
				return new Object[] { null };
			}

			Class<?> clazz = source.getClass().getComponentType();
			if (null == clazz) {
				// not an array
				return source;
			}
			else {
				if (clazz.isPrimitive()) {
					if (int.class == clazz) {
						return IntStream.of((int[]) source);
					}
					else if (long.class == clazz) {
						return LongStream.of((long[]) source);
					}
					else if (double.class == clazz) {
						return DoubleStream.of((double[]) source);
					}

					IntStream indices = IntStream.range(0, Array.getLength(source));
					IntFunction<?> boxer;
					if (boolean.class == clazz) {
						boxer = idx -> Boolean.valueOf(Array.getBoolean(source, idx));
					}
					else if (byte.class == clazz) {
						boxer = idx -> Byte.valueOf(Array.getByte(source, idx));
					}
					else if (short.class == clazz) {
						boxer = idx -> Short.valueOf(Array.getShort(source, idx));
					}
					else if (char.class == clazz) {
						boxer = idx -> Character.valueOf(Array.getChar(source, idx));
					}
					else if (float.class == clazz) {
						boxer = idx -> Float.valueOf(Array.getFloat(source, idx));
					}
					else {
						throw new IllegalArgumentException("Unexpected primitive class");
					}
					return indices.mapToObj(boxer);
				}
				else {
					Object[] objects = (Object[]) source;
					return safeCopy ? Arrays.copyOf(objects, objects.length) : source;
				}
			}
		}).map(source -> source instanceof Iterable<?>
				? StreamSupport.stream(((Iterable<?>) source).spliterator(), false).toArray()
				: source).map(
					source -> source instanceof Supplier ? new Object[] { ((Supplier<?>) source).get() } : source).map(
						source -> Boolean.class == source || boolean.class == source
								? new Object[] { Boolean.FALSE, Boolean.TRUE }
								: source).map(
									source -> source instanceof Class && ((Class<?>) source).isEnum()
											? ((Class<?>) source).getEnumConstants()
											: source).map(source -> {
												if (source instanceof Iterator) {
													List<Object> asList = new ArrayList<>();
													((Iterator<?>) source).forEachRemaining(asList::add);
													return asList.toArray();
												}
												else {
													return source;
												}
											}).map(source -> {
												if (source instanceof Enumeration) {
													List<Object> asList = new ArrayList<>();
													Enumeration<?> asEnumeration = (Enumeration<?>) source;
													while (asEnumeration.hasMoreElements()) {
														asList.add(asEnumeration.nextElement());
													}
													return asList.toArray();
												}
												else {
													return source;
												}
											}).map(source -> source instanceof IntStream ? ((IntStream) source).boxed()
													: source).map(source -> source instanceof LongStream
															? ((LongStream) source).boxed()
															: source).map(source -> source instanceof DoubleStream
																	? ((DoubleStream) source).boxed()
																	: source).map(source -> source instanceof Stream<?>
																			? ((Stream<?>) source).toArray()
																			: source).map(
																				source -> source instanceof Object[]
																						? source
																						: new Object[] {
																								source }).toArray(
																									Object[][]::new);

		IntStream.range(0, converted.length).filter(array -> 0 == converted[array].length).findFirst().ifPresent(
			badIndex -> {
				throw new IllegalArgumentException("argument must not be empty. Argument #" + badIndex);
			});

		return converted;
	}

	/**
	 * Iterates over an array of arrays returning rows consisting of all the
	 * combinations of elements from each column.
	 */
	static class ArraysSpliterator implements Spliterator<Object[]> {
		/**
		 * if true then all of the sources are considered immutable and this
		 * spliterator is imutable.
		 */
		final boolean immutable;
		/**
		 * The sources for each column position converted to materialized array
		 * instances for multiple iteration.
		 */
		final Object[][] sources;

		/**
		 * The in-progress indicies for each column position.
		 */
		final int[] valueIndices;

		/**
		 * Contains the current row values set
		 */
		final Object[] rowValues;
		/**
		 * must be twice this size to split
		 */
		final long splitThreshold;
		/**
		 * current column index to advance to next value
		 */
		int columnIndex;

		ArraysSpliterator(boolean immutable, Object[]... sources) {
			this(immutable, SPLITERATOR_SPLIT_THRESHOLD, sources);
		}

		ArraysSpliterator(boolean immutable, long splitThreshold, Object[]... sources) {
			this.immutable = immutable;
			this.splitThreshold = splitThreshold;
			this.sources = sources;
			valueIndices = new int[sources.length];
			rowValues = new Object[sources.length];
		}

		@Override
		public boolean tryAdvance(Consumer<? super Object[]> action) {
			while (columnIndex >= 0) {
				// Get the value index for the current column index
				int valueIndex = valueIndices[columnIndex];
				if (valueIndex == sources[columnIndex].length) {
					// time for next step in the prior column
					valueIndices[columnIndex--] = 0;
					continue;
				}

				// get an column value for the current column position
				rowValues[columnIndex] = sources[columnIndex][valueIndex++];
				valueIndices[columnIndex] = valueIndex;

				if (columnIndex + 1 == rowValues.length) {
					// We have a full set of column, generate a row.
					action.accept(Arrays.copyOf(rowValues, rowValues.length));
					return true;
				}
				else {
					// move to the next column position.
					columnIndex++;
				}
			}

			// no more rows available
			return false;
		}

		@Override
		public Spliterator<Object[]> trySplit() {
			if (columnIndex != 0 || (columnIndex == 0 && valueIndices[0] > 0)
					|| estimateSize() < (2 * splitThreshold)) {
				// already iterating or already done or puny number of combinations
				return null;
			}

			for (int sourceIndex = 0; sourceIndex < sources.length; sourceIndex++) {
				Object[] splitting = sources[sourceIndex];
				int size = splitting.length;
				if (1 == size) {
					continue;
				}

				int splitAt = size / 2;
				Object[][] split = Arrays.copyOf(sources, sources.length);
				split[sourceIndex] = Arrays.copyOf(splitting, splitAt);
				sources[sourceIndex] = Arrays.copyOfRange(splitting, splitAt, size);
				return new ArraysSpliterator(immutable, split);
			}

			return null;
		}

		@Override
		public long estimateSize() {
			return Arrays.stream(sources).mapToInt(Array::getLength).asLongStream().reduce(1L, Math::multiplyExact);
		}

		@Override
		public int characteristics() {
			return ORDERED | SIZED | SUBSIZED | DISTINCT | NONNULL | (immutable ? IMMUTABLE : 0);
		}
	}
}
