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


package juicebox.tools.utils.original;

import juicebox.data.ExpectedValueFunctionImpl;
import juicebox.windowui.NormalizationType;
import org.broad.igv.Globals;
import org.broad.igv.feature.Chromosome;

import java.util.*;

/**
 * Computes an "expected" density vector.  Essentially there are 3 steps to using this class
 * <p/>
 * (1) instantiate it with a collection of Chromosomes (representing a genome) and a grid size
 * (2) loop through the pair data,  calling addDistance for each pair, to accumulate all counts
 * (3) when data loop is complete, call computeDensity to do the calculation
 * <p/>
 * <p/>
 * Methods are provided to save the result of the calculation to a binary file, and restore it.  See the
 * DensityUtil class for example usage.
 *
 * @author Jim Robinson
 * @since 11/27/11
 */
public class ExpectedValueCalculation {

    private final int gridSize;

    private final int numberOfBins;
    /**
     * Map of chromosome index -> total count for that chromosome
     */
    private final Map<Integer, Double> chromosomeCounts;
    /**
     * Map of chromosome index -> "normalization factor", essentially a fudge factor to make
     * the "expected total"  == observed total
     */
    private final LinkedHashMap<Integer, Double> chrScaleFactors;
    private final NormalizationType type;
    // A little redundant, for clarity
    public boolean isFrag = false;
    /**
     * Genome wide count of binned reads at a given distance
     */
    private double[] actualDistances = null;
    /**
     * Expected count at a given binned distance from diagonal
     */
    private double[] densityAvg = null;
    /**
     * Chromosome in this genome, needed for normalizations
     */
    private Map<Integer, Chromosome> chromosomes = null;
    /**
     * Stores restriction site fragment information for fragment maps
     */
    private Map<String, Integer> fragmentCountMap;

    /**
     * Instantiate a DensityCalculation.  This constructor is used to compute the "expected" density from pair data.
     *
     * @param chromosomeList   List of chromosomes, mainly used for size
     * @param gridSize         Grid size, used for binning appropriately
     * @param fragmentCountMap Optional.  Map of chromosome name -> number of fragments
     * @param type             Identifies the observed matrix type,  either NONE (observed), VC, or KR.
     */
    public ExpectedValueCalculation(List<Chromosome> chromosomeList, int gridSize, Map<String, Integer> fragmentCountMap, NormalizationType type) {

        this.type = type;
        this.gridSize = gridSize;

        if (fragmentCountMap != null) {
            this.isFrag = true;
            this.fragmentCountMap = fragmentCountMap;
        }

        long maxLen = 0;
        this.chromosomes = new LinkedHashMap<Integer, Chromosome>();

        for (Chromosome chr : chromosomeList) {
            if (chr != null && !chr.getName().equals(Globals.CHR_ALL)) {
                chromosomes.put(chr.getIndex(), chr);
                assert fragmentCountMap != null;
                maxLen = isFrag ?
                        Math.max(maxLen, fragmentCountMap.get(chr.getName())) :
                        Math.max(maxLen, chr.getLength());
            }
        }

        numberOfBins = (int) (maxLen / gridSize) + 1;

        actualDistances = new double[numberOfBins];
        Arrays.fill(actualDistances, 0);
        chromosomeCounts = new HashMap<Integer, Double>();
        chrScaleFactors = new LinkedHashMap<Integer, Double>();

    }


    public int getGridSize() {
        return gridSize;
    }

    /**
     * Set list of chromosomes; need to do this when reading from file
     *
     * @param chromosomes1 Array of chromosomes to set
     */
    public void setChromosomes(Chromosome[] chromosomes1) {

        this.chromosomes = new LinkedHashMap<Integer, Chromosome>();
        for (Chromosome chr : chromosomes1) {
            if (chr != null) {
                chromosomes.put(chr.getIndex(), chr);
            }
        }
    }

    /**
     * Add an observed distance.  This is called for each pair in the data set
     *
     * @param chrIdx index of chromosome where observed, so can increment count
     * @param bin1   Position1 observed in units of "bins"
     * @param bin2   Position2 observed in units of "bins"
     */
    public void addDistance(Integer chrIdx, int bin1, int bin2, double weight) {

        // Ignore NaN values
        if (Double.isNaN(weight)) return;

        int dist;
        Chromosome chr = chromosomes.get(chrIdx);
        if (chr == null) return;

        Double count = chromosomeCounts.get(chrIdx);
        if (count == null) {
            chromosomeCounts.put(chrIdx, weight);
        } else {
            chromosomeCounts.put(chrIdx, count + weight);
        }
        dist = Math.abs(bin1 - bin2);


        actualDistances[dist] += weight;

    }

    public boolean hasData() {
        return !chromosomeCounts.isEmpty();
    }

    /**
     * Compute the "density" -- port of python function getDensityControls().
     * The density is a measure of the average distribution of counts genome-wide for a ligated molecule.
     * The density will decrease as distance from the center diagonal increases.
     * First compute "possible distances" for each bin.
     * "possible distances" provides a way to normalize the counts. Basically it's the number of
     * slots available in the diagonal.  The sum along the diagonal will then be the count at that distance,
     * an "expected" or average uniform density.
     */
    public void computeDensity() {

        int maxNumBins = 0;

        //System.err.println("# of bins=" + numberOfBins);
        /**
         * Genome wide binned possible distances
         */
        double[] possibleDistances = new double[numberOfBins];

        for (Chromosome chr : chromosomes.values()) {

            // didn't see anything at all from a chromosome, then don't include it in possDists.
            if (chr == null || !chromosomeCounts.containsKey(chr.getIndex())) continue;

            // use correct units (bp or fragments)
            int len = isFrag ? fragmentCountMap.get(chr.getName()) : chr.getLength();
            int nChrBins = len / gridSize;

            maxNumBins = Math.max(maxNumBins, nChrBins);

            for (int i = 0; i < nChrBins; i++) {
                possibleDistances[i] += (nChrBins - i);
            }

        }

        densityAvg = new double[maxNumBins];
        // Smoothing.  Keep pointers to window size.  When read counts drops below 400 (= 5% shot noise), smooth

        double numSum = actualDistances[0];
        double denSum = possibleDistances[0];
        int bound1 = 0;
        int bound2 = 0;
        for (int ii = 0; ii < maxNumBins; ii++) {
            if (numSum < 400) {
                while (numSum < 400 && bound2 < maxNumBins) {
                    // increase window size until window is big enough.  This code will only execute once;
                    // after this, the window will always contain at least 400 reads.
                    bound2++;
                    numSum += actualDistances[bound2];
                    denSum += possibleDistances[bound2];
                }
            } else if (numSum >= 400 && bound2 - bound1 > 0) {
                while (numSum - actualDistances[bound1] - actualDistances[bound2] >= 400) {
                    numSum = numSum - actualDistances[bound1] - actualDistances[bound2];
                    denSum = denSum - possibleDistances[bound1] - possibleDistances[bound2];
                    bound1++;
                    bound2--;
                }
            }
            densityAvg[ii] = numSum / denSum;
            // Default case - bump the window size up by 2 to keep it centered for the next iteration
            if (bound2 + 2 < maxNumBins) {
                numSum += actualDistances[bound2 + 1] + actualDistances[bound2 + 2];
                denSum += possibleDistances[bound2 + 1] + possibleDistances[bound2 + 2];
                bound2 += 2;
            } else if (bound2 + 1 < maxNumBins) {
                numSum += actualDistances[bound2 + 1];
                denSum += possibleDistances[bound2 + 1];
                bound2++;
            }
            // Otherwise, bound2 is at limit already
        }

        // Compute fudge factors for each chromosome so the total "expected" count for that chromosome == the observed

        for (Chromosome chr : chromosomes.values()) {

            if (chr == null || !chromosomeCounts.containsKey(chr.getIndex())) {
                continue;
            }
            //int len = isFrag ? fragmentCalculation.getNumberFragments(chr.getName()) : chr.getLength();
            int len = isFrag ? fragmentCountMap.get(chr.getName()) : chr.getLength();
            int nChrBins = len / gridSize;


            double expectedCount = 0;
            for (int n = 0; n < nChrBins; n++) {
                if (n < maxNumBins) {
                    final double v = densityAvg[n];
                    // this is the sum of the diagonal for this particular chromosome.
                    // the value in each bin is multiplied by the length of the diagonal to get expected count
                    // the total at the end should be the sum of the expected matrix for this chromosome
                    // i.e., for each chromosome, we calculate sum (genome-wide actual)/(genome-wide possible) == v
                    // then multiply it by the chromosome-wide possible == nChrBins - n.
                    expectedCount += (nChrBins - n) * v;

                }
            }

            double observedCount = chromosomeCounts.get(chr.getIndex());
            double f = expectedCount / observedCount;
            chrScaleFactors.put(chr.getIndex(), f);
        }
    }

    /**
     * Accessor for the normalization factors
     *
     * @return The normalization factors
     */
    public LinkedHashMap<Integer, Double> getChrScaleFactors() {
        return chrScaleFactors;
    }

    /**
     * Accessor for the densities
     *
     * @return The densities
     */
    public double[] getDensityAvg() {
        return densityAvg;
    }

    /**
     * Accessor for the normalization type
     *
     * @return The normalization type
     */
    public NormalizationType getType() {
        return type;
    }

    public ExpectedValueFunctionImpl getExpectedValueFunction() {
        computeDensity();
        return new ExpectedValueFunctionImpl(type, isFrag ? "FRAG" : "BP", gridSize, densityAvg, chrScaleFactors);
    }
}


// Smooth in 3 stages,  the window sizes are tuned to human.

//        // Smooth (1)
//        final int smoothingWidow1 = 15000000;
//        int start = smoothingWidow1 / gridSize;
//        int window = (int) (5 * (2000000f / gridSize));
//        if (window == 0) window = 1;
//        for (int i = start; i < numberOfBins; i++) {
//            int kMin = i - window;
//            int kMax = Math.min(i + window, numberOfBins);
//            double sum = 0;
//            for (int k = kMin; k < kMax; k++) {
//                sum += density[k];
//            }
//            densityAvg[i] = sum / (kMax - kMin);
//        }
//
//        // Smooth (2)
//        start = 70000000 / gridSize;
//        window = (int)(20 * (2000000f / gridSize));
//        for (int i = start; i < numberOfBins; i++) {
//            int kMin = i - window;
//            int kMax = Math.min(i + window, numberOfBins);
//            double sum = 0;
//            for (int k = kMin; k < kMax; k++) {
//                sum += density[k];
//            }
//            densityAvg[i] = sum / (kMax - kMin);
//        }
//
//        // Smooth (3)
//        start = 170000000 / gridSize;
//        for (int i = start; i < numberOfBins; i++) {
//            densityAvg[i] = densityAvg[start];
//        }


/*

--- Code above based on the following Python

gridSize => grid (or bin) size  == 10^6
actualDistances => array of actual distances,  each element represents a bin
possibleDistances => array of possible distances, each element represents a bin
jdists => outer distances between pairs

for each jdist
  actualDistance[jdist]++;


for each chromosome
  chrlen = chromosome length
  numberOfBins = chrlen / gridSize
  for each i from 0 to numberOfBins
     possibleDistances[i] += (numberOfBins - i)


for each i from 0 to maxGrid
  density[i] = actualDistance[i] / possibleDistances[i]


for each i from 0 to len(density)
 density_avg[i] = density[i]

for each i from 15000000/gridsize  to  len(density_avg)
  sum1 = 0
  for each k from (i - 5*((2*10^6) / gridSize)  to  (i + 5*((2*10^6)/gridsize))
     sum1 += density[k]
  density_avg[i] = sum1 / (10*((2*10^6)/gridsize))

for each i from 70000000/gridsize  to  len(density_avg)
  sum2 = 0
  for each k from (i - 20*((2*10^6) / gridSize)  to  (i + 20*((2*10^6)/gridsize))
     sum2 += density[k]
  density_avg[i] = sum2 / (40*((2*10^6)/gridsize))

for each i from 170000000/gridsize  to  len(density_avg)
  density_avg[i]=density_avg[170000000/gridsize]

*/
