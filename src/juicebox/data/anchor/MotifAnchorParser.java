/*
 * The MIT License (MIT)
 *
 * Copyright (c) 2011-2015 Broad Institute, Aiden Lab
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 *  THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 *  IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 *  FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 *  AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 *  LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 *  OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 *  THE SOFTWARE.
 */

package juicebox.data.anchor;

import juicebox.HiCGlobals;
import juicebox.data.HiCFileTools;
import juicebox.data.feature.FeatureFilter;
import juicebox.data.feature.GenomeWideList;
import org.broad.igv.Globals;
import org.broad.igv.feature.Chromosome;
import org.broad.igv.util.ParsingUtils;

import java.io.*;
import java.net.URL;
import java.net.URLConnection;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Created by muhammadsaadshamim on 10/26/15.
 * <p/>
 * Parses the .txt file of motifs created by FIMO
 * <p/>
 * FIMO -
 */
public class MotifAnchorParser {

    public static GenomeWideList<MotifAnchor> loadGlobalMotifs(String idOrFile, List<Chromosome> chromosomes) {

        InputStream is = null;
        Set<MotifAnchor> anchors = new HashSet<MotifAnchor>();
        BufferedReader reader = null;
        String path = "https://hicfiles.s3.amazonaws.com/internal/motifs/" + idOrFile + ".motifs.txt";

        try {

            // first assume local, then server
            File file = new File(idOrFile);
            if (file.exists()) {
                is = new FileInputStream(file);
                reader = new BufferedReader(new InputStreamReader(is), HiCGlobals.bufferSize);
            } else {
                try {

                    String tempLocalPath = downloadFromUrl(new URL(path), "motifs");
                    File file2 = new File(tempLocalPath);
                    if (file2.exists()) {
                        is = new FileInputStream(file2);
                        reader = new BufferedReader(new InputStreamReader(is), HiCGlobals.bufferSize);
                    } else {
                        System.err.println("Unable to find motif url");
                        System.exit(-4);
                    }

                } catch (Exception e2) {
                    System.err.println("Unable to find motif file");
                    System.exit(-4);
                }
            }

        } catch (Exception e) {
            System.err.println("Unable to read local motif file");
            System.exit(-4);
        } finally {

            try {
                if (reader != null) {
                    anchors.addAll(parseGlobalMotifFile(reader, chromosomes));
                }
            } catch (Exception e3) {
                //e3.printStackTrace();
                System.err.println("Unable to parse motif file");
                System.exit(-5);
            }

            if (is != null) {
                try {
                    is.close();
                } catch (Exception e4) {
                    System.err.println("Error closing file stream for motif file");
                    //e4.printStackTrace();
                }
            }
        }

        return new GenomeWideList<MotifAnchor>(chromosomes, new ArrayList<MotifAnchor>(anchors));
    }

    public static GenomeWideList<MotifAnchor> loadMotifsByGenome(String path, String genomeID, FeatureFilter<MotifAnchor> anchorFilter) {
        return loadMotifs(path, HiCFileTools.loadChromosomes(genomeID), anchorFilter);
    }

    public static GenomeWideList<MotifAnchor> loadMotifs(String path, List<Chromosome> chromosomes, FeatureFilter<MotifAnchor> anchorFilter) {
        GenomeWideList<MotifAnchor> newList = parseMotifFile(path, chromosomes, anchorFilter);
        return newList;
    }


    private static GenomeWideList<MotifAnchor> parseMotifFile(String path, List<Chromosome> chromosomes,
                                                              FeatureFilter<MotifAnchor> anchorFilter) {
        List<MotifAnchor> anchors = new ArrayList<MotifAnchor>();

        try {
            //BufferedReader br = ParsingUtils.openBufferedReader(path);
            BufferedReader br = new BufferedReader(new InputStreamReader(ParsingUtils.openInputStream(path)), HiCGlobals.bufferSize);
            anchors.addAll(parseGlobalMotifFile(br, chromosomes));
        } catch (IOException ec) {
            ec.printStackTrace();
        }

        GenomeWideList<MotifAnchor> newAnchorList = new GenomeWideList<MotifAnchor>(chromosomes, anchors);
        if (anchorFilter != null)
            newAnchorList.filterLists(anchorFilter);

        return newAnchorList;
    }

    private static List<MotifAnchor> parseGlobalMotifFile(BufferedReader br, List<Chromosome> chromosomes) throws IOException {
        Set<MotifAnchor> anchors = new HashSet<MotifAnchor>();
        String nextLine;

        // header
        nextLine = br.readLine();
        //String[] headers = Globals.tabPattern.split(nextLine);

        //pattern_name	sequence_name	start	    stop	    strand	score	p-value	    q-value	    matched sequence
        //CTCF_REN	    chr12	        121937865	121937884	+	    28.6735	5.86e-13	0.00113	    GCGGCCACCAGGGGGCGCCC

        //OR IF FILTERED LIST
        //CTCF_M1_FLIPPED	chr10:100420000-100425000	4400	4413	-	18.2569	4.1e-07	0.00811	TCCAGTAGATGGCG

        int errorCount = 0;
        while ((nextLine = br.readLine()) != null) {
            String[] tokens = Globals.tabPattern.split(nextLine);
            if (tokens.length != 9) {
                String text = "Improperly formatted FIMO output file: \npattern_name\tsequence_name\t" +
                        "start\tstop\tstrand\tscore\tp-value\tq-value\tmatched_sequence";
                System.err.println(text);
                System.exit(-5);
            }

            boolean strand;
            String chr1Name, sequence;
            int start1, end1;
            double score, pValue, qValue;

            try {
                if (tokens[1].contains(":")) {// behavior when filtered list provided
                    String[] splitToken = tokens[1].split(":");
                    String[] indices = splitToken[1].split("-");

                    chr1Name = splitToken[0];
                    start1 = Integer.parseInt(indices[0]) + Integer.parseInt(indices[2]);
                    end1 = Integer.parseInt(indices[1]) + Integer.parseInt(indices[3]);
                } else {//default format all tabs
                    chr1Name = tokens[1];
                    start1 = Integer.parseInt(tokens[2]);
                    end1 = Integer.parseInt(tokens[3]);
                }

                strand = tokens[4].contains("+");
                score = Double.parseDouble(tokens[5]);
                pValue = Double.parseDouble(tokens[6]);
                qValue = Double.parseDouble(tokens[7]);
                sequence = tokens[8];

                Chromosome chr = HiCFileTools.getChromosomeNamed(chr1Name, chromosomes);
                if (chr == null) {
                    if (HiCGlobals.printVerboseComments) {
                        if (errorCount < 10) {
                            System.out.println("Skipping line: " + nextLine);
                        } else if (errorCount == 10) {
                            System.err.println("Maximum error count exceeded.  Further errors will not be logged");
                        }
                    }

                    errorCount++;
                    continue;
                }

                MotifAnchor anchor = new MotifAnchor(chr.getIndex(), start1, end1);
                anchor.setFIMOAttributes(score, pValue, qValue, strand, sequence);

                anchors.add(anchor);

            } catch (Exception e) {
                String text = "Improperly formatted FIMO output file: \npattern_name\tsequence_name\t" +
                        "start\tstop\tstrand\tscore\tp-value\tq-value\tmatched_sequence";
                System.err.println(text);
                System.exit(-5);
            }
        }
        br.close();
        return new ArrayList<MotifAnchor>(anchors);
    }


    public static GenomeWideList<MotifAnchor> loadFromBEDFile(List<Chromosome> chromosomes, String bedFilePath) {
        List<MotifAnchor> anchors = new ArrayList<MotifAnchor>();

        try {
            //BufferedReader br = ParsingUtils.openBufferedReader(bedFilePath);
            BufferedReader br = new BufferedReader(new InputStreamReader(ParsingUtils.openInputStream(bedFilePath)), HiCGlobals.bufferSize);
            anchors.addAll(parseBEDFile(br, chromosomes));
        } catch (IOException ec) {
            ec.printStackTrace();
        }

        return new GenomeWideList<MotifAnchor>(chromosomes, anchors);
    }

    private static List<MotifAnchor> parseBEDFile(BufferedReader br, List<Chromosome> chromosomes) throws IOException {
        Set<MotifAnchor> anchors = new HashSet<MotifAnchor>();
        String nextLine;

        int errorCount = 0;
        while ((nextLine = br.readLine()) != null) {
            String[] tokens = Globals.tabPattern.split(nextLine);

            String chr1Name;
            int start1, end1;

            if (tokens[0].startsWith("chr") && tokens.length > 2) {
                // valid line
                chr1Name = tokens[0];
                start1 = Integer.parseInt(tokens[1]);
                end1 = Integer.parseInt(tokens[2]);


                Chromosome chr = HiCFileTools.getChromosomeNamed(chr1Name, chromosomes);
                if (chr == null) {
                    if (errorCount < 10) {
                        System.out.println("Skipping line: " + nextLine);
                    } else if (errorCount == 10) {
                        System.err.println("Maximum error count exceeded.  Further errors will not be logged");
                    }

                    errorCount++;
                    continue;
                }

                anchors.add(new MotifAnchor(chr.getIndex(), start1, end1));
            } else {
                continue; // header line/description; all BED files start with chr
            }
        }
        br.close();
        return new ArrayList<MotifAnchor>(anchors);
    }

    /**
     * http://kamwo.me/java-download-file-from-url-to-temp-directory/
     *
     * @param url
     * @param localFilename
     * @return
     * @throws IOException
     */
    public static String downloadFromUrl(URL url, String localFilename) throws IOException {
        InputStream is = null;
        FileOutputStream fos = null;

        String tempDir = System.getProperty("java.io.tmpdir");
        String outputPath = tempDir + "/" + localFilename;

        try {
            URLConnection urlConn = url.openConnection();
            is = urlConn.getInputStream();
            fos = new FileOutputStream(outputPath);

            byte[] buffer = new byte[HiCGlobals.bufferSize];
            int length;

            // read from source and write into local file
            while ((length = is.read(buffer)) > 0) {
                fos.write(buffer, 0, length);
            }
            return outputPath;
        } finally {
            try {
                if (is != null) {
                    is.close();
                }
            } finally {
                if (fos != null) {
                    fos.close();
                }
            }
        }
    }
}