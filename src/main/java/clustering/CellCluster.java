/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package clustering;

import clustering.util.KMeansData;
import org.apache.flink.api.common.functions.FilterFunction;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.functions.ReduceFunction;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.functions.FunctionAnnotation.ForwardedFields;
import org.apache.flink.api.java.operators.IterativeDataSet;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.fs.FileSystem;

import java.io.*;
import java.util.Collection;


public class CellCluster {

    public static void main(String[] args) throws Exception {

        // Checking input parameters
        final ParameterTool params = ParameterTool.fromArgs(args);

        // set up execution environment
        ExecutionEnvironment env = ExecutionEnvironment.getExecutionEnvironment();
        env.getConfig().setGlobalJobParameters(params); // make parameters available in the web interface


        final String temporaryCSVFile = "temporary.csv";
        final String outputFile = params.get("output");

        DataSet<CellTower> towers = getCellTowerDataSet(params, env).filter(new FilterFunction<CellTower>() {
            public boolean filter(CellTower cellTower) throws Exception {
                if (params.has("mnc")) {
                    String operators = params.get("mnc");
                    return operators.contains(cellTower.net.toString());
                }
                return true;
            }
        });

        DataSet<CellTower> lteTowers = towers.filter(new FilterFunction<CellTower>() {
            public boolean filter(CellTower cellTower) throws Exception {
                return cellTower.radio.equals("LTE");
            }
        });

        DataSet<CellTower> remainingTowers = towers.filter(new FilterFunction<CellTower>() {
            public boolean filter(CellTower cellTower) throws Exception {
                return !cellTower.radio.equals("LTE");
            }
        });

        long clusterCount = params.has("k") ? Long.parseLong(params.get("k")) : lteTowers.count();

        DataSet<Centroid> centroids = lteTowers.map(new MapFunction<CellTower, Centroid>() {
            public Centroid map(CellTower cellTower) throws Exception {
                return new Centroid(cellTower.cell.intValue(), cellTower.lon.doubleValue(), cellTower.lat.doubleValue());
            }
        }).first((int) clusterCount);

        DataSet<Point> points = remainingTowers.map(new MapFunction<CellTower, Point>() {
            public Point map(CellTower cellTower) throws Exception {
                return new Point(cellTower.lon.doubleValue(), cellTower.lat.doubleValue());
            }
        });


        // get input data:
        // read the points and centroids from the provided paths or fall back to default data
        // DataSet<Point> points = getPointDataSet(params, env);
        //DataSet<Centroid> centroids = getCentroidDataSet(params, env);


        // set number of bulk iterations for CellCluster algorithm
        IterativeDataSet<Centroid> loop = centroids.iterate(params.getInt("iterations", 10));

        DataSet<Centroid> newCentroids = points
                // compute closest centroid for each point
                .map(new SelectNearestCenter()).withBroadcastSet(loop, "centroids")
                // count and sum point coordinates for each centroid
                .map(new CountAppender())
                .groupBy(0).reduce(new CentroidAccumulator())
                // compute new centroids from point counts and coordinate sums
                .map(new CentroidAverager());

        // feed new centroids back into next iteration
        DataSet<Centroid> finalCentroids = loop.closeWith(newCentroids);

        DataSet<Tuple2<Integer, Point>> clusteredPoints = points
                // assign points to final clusters
                .map(new SelectNearestCenter()).withBroadcastSet(finalCentroids, "centroids");

        // emit result
        if (params.has("output")) {
            clusteredPoints.writeAsCsv(outputFile, "\n", ",", FileSystem.WriteMode.OVERWRITE);
            // since file sinks are lazy, we trigger the execution explicitly
            env.execute("CellCluster Example");

           /* // Append header rows hack
            try {
                // create a writer for permFile
                BufferedWriter out = new BufferedWriter(new FileWriter(outputFile, false));
                // create a reader for tmpFile
                BufferedReader in = new BufferedReader(new FileReader(temporaryCSVFile));
                String str;
                out.write("centroid,lon,lat\n");
                while ((str = in.readLine()) != null) {
                    out.write(str + "\n");
                }
                in.close();
                out.close();
                File file = new File(temporaryCSVFile);
                // file.delete();
            } catch (IOException e) {
            }*/

        } else {
            System.out.println("Printing result to stdout. Use --output to specify output path.");
            clusteredPoints.print();
        }
    }

    // *************************************************************************
    //     DATA SOURCE READING (POINTS AND CENTROIDS)
    // *************************************************************************

    private static DataSet<CellTower> getCellTowerDataSet(ParameterTool params, ExecutionEnvironment env) {
        DataSet<CellTower> towers;

        towers = env.readCsvFile(params.get("input"))
                .fieldDelimiter(",")
                .ignoreFirstLine()
                .pojoType(CellTower.class, "radio", "mcc", "net", "area", "cell", "unit", "lon", "lat", "range", "samples", "changeable", "created", "updated", "averageSignal");

        return towers;
    }

    private static DataSet<Centroid> getCentroidDataSet(ParameterTool params, ExecutionEnvironment env) {
        DataSet<Centroid> centroids;
        if (params.has("centroids")) {
            centroids = env.readCsvFile(params.get("centroids"))
                    .fieldDelimiter(" ")
                    .pojoType(Centroid.class, "id", "x", "y");
        } else {
            System.out.println("Executing K-Means example with default centroid data set.");
            System.out.println("Use --centroids to specify file input.");
            centroids = KMeansData.getDefaultCentroidDataSet(env);
        }
        return centroids;
    }

    private static DataSet<Point> getPointDataSet(ParameterTool params, ExecutionEnvironment env) {
        DataSet<Point> points;
        if (params.has("points")) {
            // read points from CSV file
            points = env.readCsvFile(params.get("points"))
                    .fieldDelimiter(" ")
                    .pojoType(Point.class, "x", "y");
        } else {
            System.out.println("Executing K-Means example with default point data set.");
            System.out.println("Use --points to specify file input.");
            points = KMeansData.getDefaultPointDataSet(env);
        }
        return points;
    }

    // *************************************************************************
    //     DATA TYPES
    // *************************************************************************

    public static class CellTower {
        public String radio;
        public Long mcc;
        public Long net;
        public Long area;
        public Long cell;
        public Long unit;
        public Float lon;
        public Float lat;
        public Long range;
        public Long samples;
        public Long changeable;
        public Long created;
        public Long updated;
        public Long averageSignal;

        public CellTower() {
        }

        public CellTower(String radio, Long mcc, Long net, Long area, Long cell, Long unit, Float lon, Float lat, Long range, Long samples, Long changeable, Long created, Long updated, Long averageSignal) {
            this.radio = radio;
            this.mcc = mcc;
            this.net = net;
            this.area = area;
            this.cell = cell;
            this.unit = unit;
            this.lon = lon;
            this.lat = lat;
            this.range = range;
            this.samples = samples;
            this.changeable = changeable;
            this.created = created;
            this.updated = updated;
            this.averageSignal = averageSignal;
        }


        @Override
        public String toString() {
            return "CellTower{" +
                    "radio='" + radio + '\'' +
                    ", mcc=" + mcc +
                    ", net=" + net +
                    ", area=" + area +
                    ", cell=" + cell +
                    ", unit=" + unit +
                    ", lon=" + lon +
                    ", lat=" + lat +
                    ", range=" + range +
                    ", samples=" + samples +
                    ", changeable=" + changeable +
                    ", created=" + created +
                    ", updated=" + updated +
                    ", averageSignal=" + averageSignal +
                    '}';
        }
    }

    /**
     * A simple two-dimensional point.
     */
    public static class Point implements Serializable {

        public double x, y;

        public Point() {
        }

        public Point(double x, double y) {
            this.x = x;
            this.y = y;
        }

        public Point add(Point other) {
            x += other.x;
            y += other.y;
            return this;
        }

        public Point div(long val) {
            x /= val;
            y /= val;
            return this;
        }

        public double euclideanDistance(Point other) {
            return Math.sqrt((x - other.x) * (x - other.x) + (y - other.y) * (y - other.y));
        }

        public void clear() {
            x = y = 0.0;
        }

        @Override
        public String toString() {
            return x + "," + y;
        }
    }

    /**
     * A simple two-dimensional centroid, basically a point with an ID.
     */
    public static class Centroid extends Point {

        public int id;

        public Centroid() {
        }

        public Centroid(int id, double x, double y) {
            super(x, y);
            this.id = id;
        }

        public Centroid(int id, Point p) {
            super(p.x, p.y);
            this.id = id;
        }

        @Override
        public String toString() {
            return id + " " + super.toString();
        }
    }

    // *************************************************************************
    //     USER FUNCTIONS
    // *************************************************************************

    /**
     * Determines the closest cluster center for a data point.
     */
    @ForwardedFields("*->1")
    public static final class SelectNearestCenter extends RichMapFunction<Point, Tuple2<Integer, Point>> {
        private Collection<Centroid> centroids;

        /**
         * Reads the centroid values from a broadcast variable into a collection.
         */
        @Override
        public void open(Configuration parameters) throws Exception {
            this.centroids = getRuntimeContext().getBroadcastVariable("centroids");
        }

        @Override
        public Tuple2<Integer, Point> map(Point p) throws Exception {

            double minDistance = Double.MAX_VALUE;
            int closestCentroidId = -1;

            // check all cluster centers
            for (Centroid centroid : centroids) {
                // compute distance
                double distance = p.euclideanDistance(centroid);

                // update nearest cluster if necessary
                if (distance < minDistance) {
                    minDistance = distance;
                    closestCentroidId = centroid.id;
                }
            }

            // emit a new record with the center id and the data point.
            return new Tuple2<Integer, Point>(closestCentroidId, p);
        }
    }

    /**
     * Appends a count variable to the tuple.
     */
    @ForwardedFields("f0;f1")
    public static final class CountAppender implements MapFunction<Tuple2<Integer, Point>, Tuple3<Integer, Point, Long>> {

        public Tuple3<Integer, Point, Long> map(Tuple2<Integer, Point> t) {
            return new Tuple3<Integer, Point, Long>(t.f0, t.f1, 1L);
        }
    }

    /**
     * Sums and counts point coordinates.
     */
    @ForwardedFields("0")
    public static final class CentroidAccumulator implements ReduceFunction<Tuple3<Integer, Point, Long>> {

        public Tuple3<Integer, Point, Long> reduce(Tuple3<Integer, Point, Long> val1, Tuple3<Integer, Point, Long> val2) {
            return new Tuple3<Integer, Point, Long>(val1.f0, val1.f1.add(val2.f1), val1.f2 + val2.f2);
        }
    }

    /**
     * Computes new centroid from coordinate sum and count of points.
     */
    @ForwardedFields("0->id")
    public static final class CentroidAverager implements MapFunction<Tuple3<Integer, Point, Long>, Centroid> {

        public Centroid map(Tuple3<Integer, Point, Long> value) {
            return new Centroid(value.f0, value.f1.div(value.f2));
        }
    }
}
