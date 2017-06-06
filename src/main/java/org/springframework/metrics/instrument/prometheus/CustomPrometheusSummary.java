/**
 * Copyright 2017 Pivotal Software, Inc.
 *
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
package org.springframework.metrics.instrument.prometheus;

import io.prometheus.client.Collector;
import org.springframework.metrics.instrument.Tag;
import org.springframework.metrics.instrument.stats.quantile.Quantiles;
import org.springframework.metrics.instrument.stats.hist.Bucket;
import org.springframework.metrics.instrument.stats.hist.Histogram;
import org.springframework.metrics.instrument.stats.hist.CumulativeHistogram;
import org.springframework.metrics.instrument.stats.hist.TimeScaleCumulativeHistogram;
import org.springframework.metrics.instrument.stats.hist.TimeScaleNormalHistogram;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.DoubleAdder;
import java.util.concurrent.atomic.LongAdder;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toList;
import static java.util.stream.StreamSupport.stream;

/**
 * Necessitated by a desire to offer different quantile algorithms.
 *
 * @author Jon Schneider
 */
public class CustomPrometheusSummary extends Collector {
    private final String name;
    private final String countName;
    private final String sumName;
    private final List<String> tagKeys;

    private final Collection<Child> children = new ConcurrentLinkedQueue<>();

    CustomPrometheusSummary(String name, List<String> tagKeys) {
        this.name = name;
        this.countName = name + "_count";
        this.sumName = name + "_sum";
        this.tagKeys = tagKeys;
    }

    Child child(Iterable<Tag> tags, Quantiles quantiles, Histogram histogram) {
        Child child = new Child(tags, quantiles, histogram);
        children.add(child);
        return child;
    }

    class Child implements CustomCollectorChild {
        private final List<String> tagValues;

        private final Quantiles quantiles;
        private List<String> quantileKeys;

        private Histogram<?> histogram;
        private List<String> histogramKeys;

        private LongAdder count = new LongAdder();
        private DoubleAdder sum = new DoubleAdder();

        Child(Iterable<Tag> tags, Quantiles quantiles, Histogram<?> histogram) {
            this.quantiles = quantiles;
            this.histogram = histogram;
            this.tagValues = stream(tags.spliterator(), false).map(Tag::getValue).collect(toList());

            if (quantiles != null) {
                quantileKeys = new LinkedList<>(tagKeys);
                quantileKeys.add("quantile");
            }

            if (histogram != null) {
                histogramKeys = new LinkedList<>(tagKeys);
                if (histogram instanceof CumulativeHistogram)
                    histogramKeys.add("le");
                else // normal histograms may or may not have buckets with a natural ordering
                    histogramKeys.add("bucket");

                if (histogram instanceof TimeScaleCumulativeHistogram) {
                    this.histogram = ((TimeScaleCumulativeHistogram) histogram).shiftScale(TimeUnit.SECONDS);
                } else if (histogram instanceof TimeScaleNormalHistogram) {
                    this.histogram = ((TimeScaleNormalHistogram) histogram).shiftScale(TimeUnit.SECONDS);
                }
            }
        }

        @Override
        public Stream<MetricFamilySamples.Sample> collect() {
            Stream.Builder<MetricFamilySamples.Sample> samples = Stream.builder();

            if (quantiles != null) {
                for (Double q : quantiles.monitored()) {
                    List<String> quantileValues = new LinkedList<>(tagValues);
                    quantileValues.add(Collector.doubleToGoString(q));
                    samples.add(new MetricFamilySamples.Sample(name, quantileKeys, quantileValues, quantiles.get(q)));
                }
            }

            if (histogram != null) {
                for (Bucket<?> b : histogram.getBuckets()) {
                    List<String> histogramValues = new LinkedList<>(tagValues);
                    histogramValues.add(b.getTag(bucket ->
                            bucket instanceof Double ? Collector.doubleToGoString((Double) bucket) : bucket.toString()));
                    samples.add(new MetricFamilySamples.Sample(name + "_bucket", histogramKeys, histogramValues, b.getValue()));
                }
            }

            samples.add(new MetricFamilySamples.Sample(countName, tagKeys, tagValues, count.sum()));
            samples.add(new MetricFamilySamples.Sample(sumName, tagKeys, tagValues, sum.sum()));

            return samples.build();
        }

        public void observe(double amt) {
            count.add(1);
            sum.add(amt);
            if (quantiles != null) {
                quantiles.observe(amt);
            }
            if (histogram != null) {
                histogram.observe(amt);
            }
        }

        public long count() {
            return count.sum();
        }

        public double sum() {
            return sum.sum();
        }
    }

    @Override
    public List<MetricFamilySamples> collect() {
        Type type = children.stream().anyMatch(c -> c.histogram != null) ? Type.HISTOGRAM : Type.SUMMARY;
        return Collections.singletonList(new MetricFamilySamples(name, type, " ", children.stream()
                .flatMap(Child::collect).collect(toList())));
    }
}
