/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.apache.flink.api.common.functions.FilterFunction;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.api.java.DataSet;
import org.apache.flink.api.java.ExecutionEnvironment;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.utils.ParameterTool;
import org.apache.flink.core.fs.FileSystem;
import org.apache.flink.util.Collector;

import java.io.*;

/**
 * Implements a streaming windowed version of the "WordCount" program.
 *
 * <p>This program connects to a server socket and reads strings from the socket.
 * The easiest way to try this out is to open a text server (at port 12345)
 * using the <i>netcat</i> tool via
 * <pre>
 * nc -l 12345
 * </pre>
 * and run this example with the hostname and the port as arguments.
 */
@SuppressWarnings("serial")
public class WordCount {

    public static void main(String[] args) throws Exception {

        final String inputFileName;
        final String outputFileName;
        final String temporaryCSVFile = "s3://cloudcomputingemr/temporary.csv";

        try {
            final ParameterTool params = ParameterTool.fromArgs(args);
            // Get the input file name
            inputFileName = params.get("input");
            // Get the output file name
            outputFileName = params.get("output");
        } catch (Exception e) {
            e.printStackTrace();
            return;
        }

        // get the execution environment
        final ExecutionEnvironment env = ExecutionEnvironment.getExecutionEnvironment();

        // Get the input DataSet from the file
        DataSet<String> text = env.readTextFile(inputFileName);

        // parse the data, filter it,  group it, and sum by count
        DataSet<Tuple2<String, Long>> windowCounts = text

                .flatMap(new FlatMapFunction<String, Tuple2<String, Long>>() {
                    public void flatMap(String value, Collector<Tuple2<String, Long>> out) {
                        for (String word : value.split("\\s")) {
                            out.collect(new Tuple2<String, Long>(word.toLowerCase()
                                    .replaceAll("[^A-Za-z]", ""), 1L));
                        }
                    }
                })

                .groupBy(0)
                .sum(1)
                .filter(new FilterFunction<Tuple2<String, Long>>() {
                    public boolean filter(Tuple2<String, Long> stringLongTuple2) throws Exception {
                        return !stringLongTuple2.f0.equals("");
                    }
                });


        windowCounts.writeAsCsv(outputFileName, FileSystem.WriteMode.OVERWRITE);


        env.execute("Socket Window WordCount");

        /*// Append header rows hack
        try {
            // create a writer for permFile
            BufferedWriter out = new BufferedWriter(new FileWriter(outputFileName, false));
            // create a reader for tmpFile
            BufferedReader in = new BufferedReader(new FileReader(temporaryCSVFile));
            String str;
            out.write("word,occurences\n");
            while ((str = in.readLine()) != null) {
                out.write(str + "\n");
            }
            in.close();
            out.close();
            File file = new File(temporaryCSVFile);
            file.delete();
        } catch (IOException e) {
        }*/
    }

}