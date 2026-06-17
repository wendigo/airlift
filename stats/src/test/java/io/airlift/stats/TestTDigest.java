/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.airlift.stats;

import com.google.common.collect.ImmutableList;
import com.google.common.io.Resources;
import com.google.common.primitives.Doubles;
import io.airlift.slice.Slice;
import io.airlift.slice.Slices;
import org.assertj.core.data.Offset;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.IntStream;

import static com.google.common.collect.ImmutableList.toImmutableList;
import static java.util.Arrays.asList;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

public class TestTDigest
{
    @Test
    public void testScaledMergeWithSelf()
    {
        TDigest digest = new TDigest();
        digest.add(10, 4);

        digest.mergeWith(digest, 2);

        assertThat(digest.getCount()).isEqualTo(6);
        assertThat(digest.valueAt(0.5)).isEqualTo(10);
    }

    @Test
    public void testScaledMergeDoesNotCompactSource()
    {
        TDigest source = new TDigest();
        for (int i = 0; i < 1000; i++) {
            source.add(i, 10);
        }
        TDigest target = new TDigest();

        target.mergeWith(source, 2);

        assertThat(source.getCount()).isEqualTo(10_000);
        assertThat(target.getCount()).isEqualTo(5_000);
    }

    @Test
    public void testEmpty()
    {
        TDigest digest = new TDigest();
        assertThat(digest.valueAt(0.5)).isNaN();
        assertThat(digest.getMin()).isNaN();
        assertThat(digest.getMax()).isNaN();
        double[] quantiles = new double[] {0.1, 0.2, 0.5, 0.9};
        double[] expected = new double[] {Double.NaN, Double.NaN, Double.NaN, Double.NaN};
        assertThat(digest.valuesAt(quantiles)).isEqualTo(expected);
        assertThat(digest.valuesAt(Doubles.asList(quantiles)))
                .allSatisfy(value -> assertThat(value).isNaN());
    }

    @Test
    public void testMonotonicity()
    {
        TDigest digest = new TDigest();
        for (int i = 0; i < 100000; i++) {
            digest.add(ThreadLocalRandom.current().nextDouble());
        }

        double previous = -1;
        for (double quantile = 0; quantile <= 1; quantile += 1e-5) {
            double value = digest.valueAt(quantile);
            assertThat(value >= previous).isTrue();
            previous = value;
        }
    }

    @Test
    public void testBigJump()
    {
        TDigest digest = new TDigest(100);
        for (int i = 1; i < 20; i++) {
            digest.add(i);
        }
        digest.add(1_000_000);

        assertThat(digest.valueAt(0.89999999)).isEqualTo(18.0);
        assertThat(digest.valueAt(0.9)).isEqualTo(19.0);
        assertThat(digest.valueAt(0.949999999)).isEqualTo(19.0);
        assertThat(digest.valueAt(0.95)).isEqualTo(1_000_000.0);
        assertThat(digest.valuesAt(0.89999999, 0.9, 0.949999999, 0.95)).isEqualTo(new double[] {18.0, 19.0, 19.0, 1_000_000.0});
    }

    @Test
    public void testBigJumpWithMerge()
    {
        TDigest digest = new TDigest(100);
        for (int i = 1; i < 1000; i++) {
            digest.add(i);
        }
        digest.add(1_000_000);

        assertThat(digest.valueAt(0.998)).isEqualTo(999.0);
        assertThat(digest.valueAt(0.999)).isEqualTo(1_000_000.0);
        assertThat(digest.valuesAt(0.998, 0.999)).isEqualTo(new double[] {999.0, 1_000_000.0});
    }

    @Test
    public void testSmallCountQuantile()
    {
        TDigest digest = new TDigest(200);
        addAll(digest, new ArrayList<>(Arrays.asList(15, 20, 32, 60)));

        assertThat(digest.valueAt(0.4)).isCloseTo(20, within(1e-10));
        assertThat(digest.valueAt(0.25)).isCloseTo(20, within(1e-10));
        assertThat(digest.valueAt(0.25 - 1e-10)).isCloseTo(15, within(1e-10));
        assertThat(digest.valueAt(0.5 - 1e-10)).isCloseTo(20, within(1e-10));
        assertThat(digest.valueAt(0.5)).isCloseTo(32, within(1e-10));

        double[] quantiles = new double[] {0.25 - 1e-10, 0.25, 0.4, 0.5 - 1e-10, 0.5};
        double[] values = digest.valuesAt(quantiles);
        for (int i = 0; i < quantiles.length; i++) {
            assertThat(values[i]).isEqualTo(digest.valueAt(quantiles[i]));
        }
    }

    @Test
    public void testSingletonQuantiles()
    {
        double[] values = new double[20];
        TDigest digest = new TDigest(100);
        for (int i = 0; i < 20; i++) {
            digest.add(i);
            values[i] = i;
        }

        List<Double> quantiles = IntStream.range(0, 1000)
                .mapToDouble(i -> i * 1e-3)
                .boxed()
                .collect(toImmutableList());
        List<Double> expectedValues = quantiles.stream()
                .map(quantile -> values[(int) Math.floor(quantile * values.length)])
                .collect(toImmutableList());
        List<Double> valuesAtQuantilesSingle = quantiles.stream()
                .map(digest::valueAt)
                .collect(toImmutableList());
        List<Double> valuesAtQuantilesMultiple = digest.valuesAt(quantiles);
        assertThat(expectedValues).isEqualTo(valuesAtQuantilesSingle);
        assertThat(expectedValues).isEqualTo(valuesAtQuantilesMultiple);
    }

    @Test
    public void testSingleValue()
    {
        TDigest digest = new TDigest();
        double value = ThreadLocalRandom.current().nextDouble() * 1000;
        digest.add(value);

        assertThat(digest.valueAt(0)).isCloseTo(value, within(0.001));
        assertThat(digest.valueAt(0.5)).isCloseTo(value, within(0.001));
        assertThat(digest.valueAt(1)).isCloseTo(value, within(0.001));
        assertThat(digest.valuesAt(0d, 0.5d, 1d)).isEqualTo(new double[] {digest.valueAt(0), digest.valueAt(0.5), digest.valueAt(1)});
    }

    @Test
    public void testWeight()
    {
        TDigest digest = new TDigest();
        digest.add(1, 80);
        digest.add(2, 20);

        assertThat(digest.valueAt(0)).isEqualTo(1.0);
        assertThat(digest.valueAt(0.3)).isEqualTo(1.0);
        assertThat(digest.valueAt(0.9)).isEqualTo(2.0);
        assertThat(digest.valueAt(1)).isEqualTo(2.0);
        assertThat(digest.valuesAt(0d, 0.3d, 0.9d, 1d)).isEqualTo(new double[] {1.0, 1.0, 2.0, 2.0});
    }

    @Test
    public void testFirstInnerAndLastCentroid()
    {
        TDigest digest = new TDigest();
        digest.add(1);
        digest.add(2);
        digest.add(3);
        digest.add(4);

        assertThat(digest.valuesAt(0d, 0.6d, 1d)).isEqualTo(new double[] {1.0, 3.0, 4.0});
    }

    @Test
    public void testSerializationEmpty()
    {
        TDigest digest = new TDigest();
        TDigest deserialized = TDigest.deserialize(digest.serialize());

        assertSimilar(deserialized, digest);

        // ensure the internal arrays are initialized properly
        deserialized.add(10);
        assertThat(deserialized.getCount()).isEqualTo(1.0);
        assertThat(deserialized.valueAt(0.5)).isEqualTo(10.0);
    }

    @Test
    public void testSerializationSingle()
    {
        TDigest digest = new TDigest();
        digest.add(1);

        TDigest deserialized = TDigest.deserialize(digest.serialize());

        assertSimilar(deserialized, digest);
        assertThat(deserialized.valueAt(0)).isEqualTo(digest.valueAt(0));
        assertThat(deserialized.valueAt(1)).isEqualTo(digest.valueAt(1));
    }

    @Test
    public void testSerializationComplex()
    {
        TDigest digest = new TDigest();
        addAll(digest, asList(0, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 4, 5, 6, 7));

        TDigest deserialized = TDigest.deserialize(digest.serialize());

        assertSimilar(deserialized, digest);

        for (double quantile = 0; quantile <= 1; quantile += 0.1) {
            assertThat(deserialized.valueAt(quantile)).isEqualTo(digest.valueAt(quantile));
        }
    }

    @RepeatedTest(1000)
    public void testSerializationRandom()
    {
        TDigest digest = new TDigest();

        List<Integer> values = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            values.add(ThreadLocalRandom.current().nextInt());
        }

        addAll(digest, values);

        TDigest deserialized = TDigest.deserialize(digest.serialize());

        assertSimilar(deserialized, digest);

        for (double quantile = 0; quantile <= 1; quantile += 0.1) {
            assertThat(deserialized.valueAt(quantile)).isEqualTo(digest.valueAt(quantile));
        }
    }

    @Test
    public void testCompactSerializationEmpty()
    {
        TDigest digest = new TDigest();
        TDigest deserialized = TDigest.deserialize(digest.serializeCompact());

        assertSimilar(deserialized, digest);

        deserialized.add(10);
        assertThat(deserialized.getCount()).isEqualTo(1.0);
        assertThat(deserialized.valueAt(0.5)).isEqualTo(10.0);
    }

    @Test
    public void testCompactSerializationSingle()
    {
        TDigest digest = new TDigest();
        digest.add(1);

        TDigest deserialized = TDigest.deserialize(digest.serializeCompact());

        assertSimilar(deserialized, digest);
        assertThat(deserialized.valueAt(0)).isEqualTo(digest.valueAt(0));
        assertThat(deserialized.valueAt(1)).isEqualTo(digest.valueAt(1));
    }

    @Test
    public void testCompactSerializationComplex()
    {
        TDigest digest = new TDigest();
        addAll(digest, asList(0, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 4, 5, 6, 7));

        assertCompactRoundTrip(digest);
    }

    @Test
    public void testCompactSerializationNegativeAndLargeValues()
    {
        TDigest digest = new TDigest();
        addAll(digest, asList(-1_000_000, -5, -1, 0, 1, 5, 1_000_000));

        assertCompactRoundTrip(digest);
    }

    @Test
    public void testCompactSerializationFractionalWeights()
    {
        // exercises the non-integral weight path, which falls back to fixed-width doubles
        TDigest digest = new TDigest();
        digest.add(1, 0.5);
        digest.add(2, 1.25);
        digest.add(3, 99999.999999999);

        assertCompactRoundTrip(digest);
    }

    @Test
    public void testCompactIsSmallerForIntegerData()
    {
        TDigest digest = new TDigest();
        for (int i = 0; i < 10_000; i++) {
            digest.add(i);
        }

        assertThat(digest.serializeCompact().length()).isLessThan(digest.serializedSizeInBytes() * 2 / 3);
    }

    @Test
    public void testCompactWorstCaseIsBoundedByLegacyPlusNibbles()
    {
        // means 16 binades apart need full-width deltas and fractional weights cannot be integers;
        // a non-default compression and a merge that moves min/max off the edge means fill the header
        TDigest digest = new TDigest(50);
        for (int i = -60; i <= 60; i++) {
            digest.add(Math.scalb(1.0, 16 * i), 0.5);
        }
        TDigest other = new TDigest(50);
        other.add(-Double.MAX_VALUE, 0.25);
        other.add(Double.MAX_VALUE, 0.25);
        digest.mergeWith(other);

        // a full header is as long as the legacy one; each section adds a width byte per group of eight
        int centroids = (digest.serializeLegacy().length() - 37) / 16;
        assertThat(digest.serializeCompact().length()).isLessThanOrEqualTo(digest.serializeLegacy().length() + centroids + 1);
        assertBitwiseCompactRoundTrip(digest);
    }

    @Test
    public void testSerializeWritesLegacyFormat()
    {
        TDigest digest = new TDigest();
        for (int i = 0; i < 10_000; i++) {
            digest.add(i);
        }

        assertThat(digest.serialize()).isEqualTo(digest.serializeLegacy());
    }

    @Test
    public void testLegacySerializationRoundTrip()
    {
        TDigest digest = new TDigest();
        addAll(digest, asList(0, 2, 2, 2, 2, 3, 3, 3, 3, 3, 3, 4, 5, 6, 7));

        // the legacy fixed-width encoder remains readable via deserialize()
        TDigest deserialized = TDigest.deserialize(digest.serializeLegacy());

        assertSimilar(deserialized, digest);
        for (double quantile = 0; quantile <= 1; quantile += 0.1) {
            assertThat(deserialized.valueAt(quantile)).isEqualTo(digest.valueAt(quantile));
        }
    }

    @RepeatedTest(1000)
    public void testCompactSerializationRandom()
    {
        TDigest digest = new TDigest();

        List<Integer> values = new ArrayList<>();
        for (int i = 0; i < 1000; i++) {
            values.add(ThreadLocalRandom.current().nextInt());
        }

        addAll(digest, values);

        assertCompactRoundTrip(digest);
    }

    @Test
    public void testDeserializeInvalidFormat()
    {
        assertThatThrownBy(() -> TDigest.deserialize(Slices.wrappedBuffer((byte) 99)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid format");
    }

    @Test
    public void testCompactSingleValueRoundTrip()
    {
        double[] values = {0.0, -0.0, 1, -1, 3.14159, -2.5e300, Double.MIN_VALUE, Double.MAX_VALUE};
        for (double value : values) {
            for (double weight : new double[] {1, 7, 127, 128, 0.5, 0x1p56, 0x1p63, 1e20}) {
                for (double compression : new double[] {TDigest.DEFAULT_COMPRESSION, 50}) {
                    TDigest digest = new TDigest(compression);
                    digest.add(value, weight);
                    assertBitwiseCompactRoundTrip(digest);
                }
            }
        }
    }

    @Test
    public void testCompactSingleValueIsSmall()
    {
        TDigest digest = new TDigest();
        digest.add(1_234_567);

        // tag + flags + count + raw mean + width byte + the 1-bit weight in one byte
        assertThat(digest.serializeCompact().length()).isEqualTo(13);
        assertCompactRoundTrip(digest);
    }

    @Test
    public void testCompactEmptyDigestRoundTrip()
    {
        assertBitwiseCompactRoundTrip(new TDigest());
        assertBitwiseCompactRoundTrip(new TDigest(50));
    }

    @Test
    public void testCompactMinMaxDifferentFromMeans()
    {
        // min and max lie outside the surviving centroid means after a merge of unequal partials
        TDigest digest = new TDigest();
        digest.add(5, 10);
        TDigest other = new TDigest();
        other.add(-3);
        other.add(100);
        digest.mergeWith(other);

        assertBitwiseCompactRoundTrip(digest);
    }

    @Test
    public void testCompactVarintBoundaries()
    {
        // mean deltas and weights that straddle every varint length, including the 9-byte escape
        TDigest digest = new TDigest();
        for (int shift = 0; shift < 63; shift += 7) {
            digest.add(Math.scalb(1.0, shift), Math.max(1, Math.scalb(1.0, shift) - 1));
            digest.add(Math.scalb(1.0, shift) + Math.ulp(Math.scalb(1.0, shift)), Math.scalb(1.0, shift));
        }
        digest.add(-Double.MAX_VALUE);
        digest.add(Double.MAX_VALUE);
        assertBitwiseCompactRoundTrip(digest);
    }

    @Test
    public void testCompactDeserializeFromSlicedView()
    {
        TDigest digest = new TDigest();
        for (int i = 0; i < 1000; i++) {
            digest.add(i * 1.5, 3);
        }
        Slice compressed = digest.serializeCompact();

        // a view at the very end of a larger buffer exercises the byte-at-a-time path near the array end
        Slice buffer = Slices.allocate(compressed.length() + 5);
        buffer.setBytes(5, compressed);
        TDigest deserialized = TDigest.deserialize(buffer.slice(5, compressed.length()));
        assertThat(deserialized.serializeLegacy()).isEqualTo(digest.serializeLegacy());

        // a view with trailing bytes beyond the digest must not be confused by them
        Slice padded = Slices.allocate(compressed.length() + 16);
        padded.setBytes(0, compressed);
        byte[] garbage = new byte[16];
        Arrays.fill(garbage, (byte) 0xFF);
        padded.setBytes(compressed.length(), garbage);
        deserialized = TDigest.deserialize(padded.slice(0, compressed.length()));
        assertThat(deserialized.serializeLegacy()).isEqualTo(digest.serializeLegacy());
    }

    @Test
    public void testCompactDeserializeTruncated()
    {
        TDigest digest = new TDigest();
        for (int i = 0; i < 100; i++) {
            digest.add(i);
        }
        Slice compressed = digest.serializeCompact();
        for (int length = 2; length < compressed.length(); length += 7) {
            Slice truncated = compressed.slice(0, length);
            assertThatThrownBy(() -> TDigest.deserialize(truncated))
                    .isInstanceOfAny(IllegalArgumentException.class, IndexOutOfBoundsException.class);
        }
    }

    private void assertBitwiseCompactRoundTrip(TDigest digest)
    {
        TDigest deserialized = TDigest.deserialize(digest.serializeCompact());
        assertThat(deserialized.serializeLegacy()).isEqualTo(digest.serializeLegacy());
    }

    private void assertCompactRoundTrip(TDigest digest)
    {
        TDigest deserialized = TDigest.deserialize(digest.serializeCompact());

        assertSimilar(deserialized, digest);

        // the compact format is lossless, so the reconstructed quantiles must match exactly
        for (double quantile = 0; quantile <= 1; quantile += 0.1) {
            assertThat(deserialized.valueAt(quantile)).isEqualTo(digest.valueAt(quantile));
        }
    }

    @Test
    public void testAddNaN()
    {
        TDigest digest = new TDigest();

        assertThatThrownBy(() -> digest.add(1, Double.NaN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> digest.add(Double.NaN, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    public void testAddNonPositiveWeight()
    {
        TDigest digest = new TDigest();
        digest.add(1, 5);

        assertThatThrownBy(() -> digest.add(2, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("weight must be positive");
        assertThatThrownBy(() -> digest.add(2, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("weight must be positive");

        // a rejected add must not mutate digest state
        assertThat(digest.getCount()).isEqualTo(5.0);
        assertThat(digest.getMin()).isEqualTo(1.0);
        assertThat(digest.getMax()).isEqualTo(1.0);
    }

    @Test
    public void testCopy()
    {
        TDigest digest = new TDigest();
        digest.add(1);
        digest.add(2);
        digest.add(3, 10);

        TDigest copy = TDigest.copyOf(digest);
        assertSimilar(copy, digest);

        for (double quantile = 0; quantile <= 1; quantile += 0.1) {
            assertThat(copy.valueAt(quantile)).isEqualTo(digest.valueAt(quantile));
        }
    }

    @Test
    public void testCopyEmpty()
    {
        TDigest digest = new TDigest();
        TDigest copy = TDigest.copyOf(digest);
        assertSimilar(copy, digest);

        // ensure the internal arrays are initialized properly
        copy.add(10);
        assertThat(copy.getCount()).isEqualTo(1.0);
        assertThat(copy.valueAt(0.5)).isEqualTo(10.0);
    }

    @Test
    public void testCompact()
    {
        TDigest digest = new TDigest();
        for (int i = 0; i < 10_000; i++) {
            digest.add(i);
        }

        double[] quantiles = {0, 0.1, 0.25, 0.5, 0.75, 0.9, 0.99, 1};

        // merge up front so that compact() only trims and does not change the contents
        double[] expected = digest.valuesAt(quantiles);
        int centroidCount = digest.getCentroidCount();
        int sizeBeforeCompact = digest.estimatedInMemorySizeInBytes();

        digest.compact();

        // compaction preserves the centroids and the computed quantiles
        assertThat(digest.getCentroidCount()).isEqualTo(centroidCount);
        assertThat(digest.valuesAt(quantiles)).isEqualTo(expected);

        // and reclaims memory
        assertThat(digest.estimatedInMemorySizeInBytes()).isLessThan(sizeBeforeCompact);

        // a compacted digest still serializes and round-trips correctly
        TDigest deserialized = TDigest.deserialize(digest.serialize());
        assertSimilar(deserialized, digest);
        assertThat(deserialized.valuesAt(quantiles)).isEqualTo(digest.valuesAt(quantiles));

        // and still accepts new values, re-growing its buffers on demand
        digest.add(10_000);
        assertThat(digest.getCount()).isEqualTo(10_001.0);
    }

    @Test
    public void testCompactEmpty()
    {
        TDigest digest = new TDigest();
        digest.compact();

        // a compacted empty digest is still usable
        digest.add(10);
        assertThat(digest.getCount()).isEqualTo(1.0);
        assertThat(digest.valueAt(0.5)).isEqualTo(10.0);
    }

    @Test
    public void testMerge()
    {
        TDigest first = new TDigest();
        addAll(first, Arrays.asList(1, 2, 3, 4, 5));

        TDigest second = new TDigest();
        addAll(second, Arrays.asList(4, 5, 6, 7, 8));

        TDigest merged = TDigest.copyOf(first);
        merged.mergeWith(second);

        assertThat(merged.getMin()).isEqualTo(1.0);
        assertThat(merged.getMax()).isEqualTo(8.0);
        assertThat(merged.getCount()).isEqualTo(10.0);

        assertThat(merged.valueAt(0)).isEqualTo(1.0);
        assertThat(merged.valueAt(0.5)).isEqualTo(5.0);
        assertThat(merged.valueAt(1)).isEqualTo(8.0);
    }

    @Test
    public void testUnmergeable()
            throws IOException
    {
        // Tests an edge case where centroids cannot be merged and an attempting to add another value fails because of lack of room in the buffer
        byte[] serialized = Resources.toByteArray(Resources.getResource("io/airlift/stats/unmergeable-tdigest"));
        TDigest digest = TDigest.deserialize(Slices.wrappedBuffer(serialized));

        // validate the assumption
        int centroids = digest.getCentroidCount();
        digest.forceMerge();
        assertThat(digest.getCentroidCount()).as("Assumption that digest is not mergeable no longer holds").isEqualTo(centroids);

        for (int i = 0; i < 1000; i++) {
            // add some values somewhere in the middle of the distribution
            digest.add(interpolate(ThreadLocalRandom.current().nextGaussian(), -1, digest.getMin(), 1, digest.getMax()));
        }
    }

    @Test
    public void testTwoValueTDigest()
    {
        TDigest digest = new TDigest();
        digest.add(10, 99999.999999999);
        digest.add(10, 99999.999999999);
        assertThat(10.0).isEqualTo(digest.valueAt(0.75));

        digest = new TDigest();
        digest.add(10, 99999.999999999);
        digest.add(20, 99999.999999999);
        assertThat(20.0).isEqualTo(digest.valueAt(0.75));
    }

    @Test
    public void testValuesAtSimpleCases()
    {
        TDigest digest = new TDigest();

        // empty quantiles list
        assertThat(ImmutableList.of()).isEqualTo(digest.valuesAt(ImmutableList.of()));
        assertThat(new double[0]).isEqualTo(digest.valuesAt());
        assertThat(new double[0]).isEqualTo(digest.valuesAt());

        // quantiles not sorted
        assertThatThrownBy(() -> digest.valuesAt(ImmutableList.of(0.9, 0.1)))
                .isInstanceOf(IllegalArgumentException.class);

        // quantile out of range [0, 1]
        assertThatThrownBy(() -> digest.valuesAt(ImmutableList.of(-0.9, 0.9)))
                .isInstanceOf(IllegalArgumentException.class);

        // empty digest
        assertThat(ImmutableList.of(Double.NaN, Double.NaN)).isEqualTo(digest.valuesAt(ImmutableList.of(0.5, 0.75)));

        // single centroid
        digest.add(10);
        assertThat(ImmutableList.of(10.0, 10.0, 10.0, 10.0)).isEqualTo(digest.valuesAt(ImmutableList.of(0.0, 0.5, 0.75, 1.0)));
    }

    @Test
    public void testValuesAt()
    {
        TDigest digest = new TDigest();
        addAll(digest, ImmutableList.of(10, 20, 30, 40));
        // quantiles at centroid borders
        assertThat(ImmutableList.of(10.0, 20.0, 30.0, 40.0, 40.0)).isEqualTo(digest.valuesAt(ImmutableList.of(0.0, 0.25, 0.5, 0.75, 1.0)));
        // quantiles inside centroids
        assertThat(ImmutableList.of(10.0, 10.0, 20.0, 20.0, 30.0, 30.0, 40.0, 40.0)).isEqualTo(digest.valuesAt(ImmutableList.of(0.001, 0.249, 0.251, 0.499, 0.501, 0.749, 0.751, 0.999)));

        digest = new TDigest();
        digest.add(10, 2);
        digest.add(20, 2);
        // min value and value at offset 1
        assertThat(ImmutableList.of(10.0, 10.0)).isEqualTo(digest.valuesAt(ImmutableList.of(0.1, 0.25)));
        // short-circuit to last centroid
        assertThat(ImmutableList.of(20.0, 20.0)).isEqualTo(digest.valuesAt(ImmutableList.of(0.9, 1.0)));
        assertThat(ImmutableList.of(20.0, 20.0)).isEqualTo(digest.valuesAt(ImmutableList.of(0.75, 1.0)));
        // pass through the structure until last centroid
        assertThat(ImmutableList.of(15.0, 20.0)).isEqualTo(digest.valuesAt(ImmutableList.of(0.5, 1.0)));

        digest = new TDigest();
        digest.add(10, 4);
        digest.add(20, 4);
        // min value and value at offset 1
        assertThat(ImmutableList.of(10.0, 10.0)).isEqualTo(digest.valuesAt(ImmutableList.of(0.1, 0.125)));
        // short-circuit to last centroid
        assertThat(ImmutableList.of(20.0, 20.0, 20.0)).isEqualTo(digest.valuesAt(ImmutableList.of(0.75, 0.875, 1.0)));

        digest = new TDigest();
        digest.add(10, 4);
        digest.add(20, 1);
        digest.add(30, 1);
        digest.add(40, 2);
        digest.add(50, 2);
        // single-element vs multi-element clusters
        assertThat(ImmutableList.of(19.5, 20.0, 20.0, 30.0, 30.0, 30.999999999999996, 44.5, 45.50000000000001)).isEqualTo(digest.valuesAt(ImmutableList.of(0.39, 0.41, 0.49, 0.51, 0.59, 0.61, 0.79, 0.81)));
    }

    private void addAll(TDigest digest, List<Integer> values)
    {
        for (int value : values) {
            digest.add(value);
        }
    }

    private void assertSimilar(TDigest actual, TDigest expected)
    {
        assertThat(actual.getMin()).isEqualTo(expected.getMin(), Offset.offset(0.0));
        assertThat(actual.getMax()).isEqualTo(expected.getMax(), Offset.offset(0.0));
        assertThat(actual.getCount()).isEqualTo(expected.getCount(), Offset.offset(0.0));
    }

    private static double interpolate(double x, double x0, double y0, double x1, double y1)
    {
        return y0 + (x - x0) / (x1 - x0) * (y1 - y0);
    }
}
