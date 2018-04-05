/*******************************************************************************
 * NGSEP - Next Generation Sequencing Experience Platform
 * Copyright 2016 Jorge Duitama
 *
 * This file is part of NGSEP.
 *
 *     NGSEP is free software: you can redistribute it and/or modify
 *     it under the terms of the GNU General Public License as published by
 *     the Free Software Foundation, either version 3 of the License, or
 *     (at your option) any later version.
 *
 *     NGSEP is distributed in the hope that it will be useful,
 *     but WITHOUT ANY WARRANTY; without even the implied warranty of
 *     MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *     GNU General Public License for more details.
 *
 *     You should have received a copy of the GNU General Public License
 *     along with NGSEP.  If not, see <http://www.gnu.org/licenses/>.
 *******************************************************************************/
package ngsep.discovery;

import java.io.IOException;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Logger;

import ngsep.discovery.rd.ReadDepthBin;
import ngsep.discovery.rd.ReadDepthDistribution;
import ngsep.discovery.rd.SingleSampleReadDepthAlgorithm;
import ngsep.genome.GenomicRegion;
import ngsep.genome.GenomicRegionSortedCollection;
import ngsep.genome.GenomicRegionSpanComparator;
import ngsep.genome.ReferenceGenome;
import ngsep.genome.io.SimpleGenomicRegionFileHandler;
import ngsep.main.CommandsDescriptor;
import ngsep.main.ProgressNotifier;
import ngsep.sequences.AbstractLimitedSequence;
import ngsep.sequences.QualifiedSequence;
import ngsep.sequences.QualifiedSequenceList;
import ngsep.variants.CalledCNV;
import ngsep.variants.CalledGenomicVariant;
import ngsep.variants.CalledSNV;
import ngsep.variants.GenomicVariant;
import ngsep.variants.GenomicVariantAnnotation;
import ngsep.variants.GenomicVariantImpl;
import ngsep.variants.Sample;
import ngsep.variants.io.GFFVariantsFileHandler;
import ngsep.vcf.VCFFileHeader;
import ngsep.vcf.VCFFileReader;
import ngsep.vcf.VCFFileWriter;
import ngsep.vcf.VCFRecord;


public class VariantsDetector implements PileupListener {
	
	public static final short DEF_MINSVQUALITY = 20;
	private Logger log;
	private AlignmentsPileupGenerator generator = new AlignmentsPileupGenerator();
	
	//Required input fields
	private String referenceFile=null;
	private String alignmentsFile=null;
	
	//Optional fields
	private byte normalPloidy = 2;
	private String knownSVsFile=null;
	private String knownSTRsFile=null;
	private String knownVariantsFile=null;
	private boolean findRepeats = true;
	private boolean runRDAnalysis = true;
	private boolean findSNVs = true;
	private boolean runRPAnalysis = true;
	private boolean findNewCNVs = true;
	private String algCNV = "CNVnator";
	private String sampleId = "Sample";
	private boolean printSamplePloidy = false;
	private int binSize = ReadDepthDistribution.DEFAULT_BIN_SIZE;
	private long inputGenomeSize = 0;
	private short minSVQuality = DEF_MINSVQUALITY;
	private int maxPCTOverlapCNVs = 100;
	
	//Parameter objects
	private ReferenceGenome genome;
	
	//Output 
	private GenomicRegionSortedCollection<CalledGenomicVariant> calledSVs;
	private List<CalledGenomicVariant> calledVars =new ArrayList<CalledGenomicVariant>();
	
	//File handlers
	private VCFFileWriter varsFW = new VCFFileWriter();
	private VCFFileHeader header;
	private GFFVariantsFileHandler svsFH = new GFFVariantsFileHandler();
	
	//Classes implementing the algorithms for structural variants detection
	private MultipleMappingRegionsCalculator mmRegsCalc = new MultipleMappingRegionsCalculator();
	private ReadPairAnalyzer rpAnalyzer = new ReadPairAnalyzer();
	
	//Listeners
	private IndelRealignerPileupListener indelRealigner = new IndelRealignerPileupListener();
	private VariantPileupListener varListener = new VariantPileupListener();
	
	//Output streams
	private PrintStream outVars = null;
	private PrintStream outStructural = null;
	
	//Progress tracking for external control
	private ProgressNotifier progressNotifier = null;
	private double coveredGenomeSize = 0;
	private long referenceGenomeSize = 0;
	
	

	/**
	 * @param args
	 * @throws Exception 
	 */
	public static void main(String[] args) throws Exception {
		if (args.length == 0 || args[0].equals("--help")){
			CommandsDescriptor.getInstance().printHelp(VariantsDetector.class);
			return;
		}
		VariantsDetector detector = new VariantsDetector();
		detector.setLog(Logger.getLogger(VariantsDetector.class.getName()));
		double hetRate = -1;
		
		int i=0;
		while(i<args.length && args[i].charAt(0)=='-') {
			if("-querySeq".equals(args[i])) {
				i++;
				detector.setQuerySeq(args[i]);
			} else if("-first".equals(args[i])) {
				i++;
				detector.setQueryFirst(Integer.parseInt(args[i]));
			} else if("-last".equals(args[i])) {
				i++;
				detector.setQueryLast(Integer.parseInt(args[i]));
			} else if("-h".equals(args[i])) {
				i++;
				hetRate = Double.parseDouble(args[i]);
				detector.setHeterozygosityRate(hetRate);
			} else if("-ignoreLowerCaseRef".equals(args[i])) {
				detector.setIgnoreLowerCaseRef(true);
			} else if("-maxAlnsPerStartPos".equals(args[i])) {
				//Default 5
				i++;
				detector.setMaxAlnsPerStartPos(Integer.parseInt(args[i]));
			} else if("-u".equals(args[i])) {
				detector.setProcessOnlyUniqueAlignments(true);
			} else if("-s".equals(args[i])) {
				detector.setProcessSecondaryAlignments(true);
			} else if("-minAltCoverage".equals(args[i])) {
				//Default 0
				i++;
				detector.setMinAltCoverage(Integer.parseInt(args[i]));
			} else if("-maxAltCoverage".equals(args[i])) {
				//Default 0 (No filter)
				i++;
				detector.setMaxAltCoverage(Integer.parseInt(args[i]));
			} else if("-minQuality".equals(args[i])) {
				//Default 0 (No filter)
				i++;
				detector.setMinQuality(Short.parseShort(args[i]));
			} else if("-maxBaseQS".equals(args[i])) {
				//Default 0
				i++;
				detector.setMaxBaseQS(Short.parseShort(args[i]));
			} else if("-ignore5".equals(args[i])) {
				i++;
				detector.setBasesToIgnore5P(Byte.parseByte(args[i]));
			} else if("-ignore3".equals(args[i])) {
				i++;
				detector.setBasesToIgnore3P(Byte.parseByte(args[i]));
			} else if("-ploidy".equals(args[i])) {
				i++;
				detector.setNormalPloidy(Byte.parseByte(args[i]));
			} else if("-knownSVs".equals(args[i])) {
				i++;
				detector.setKnownSVsFile(args[i]);
			} else if("-knownSTRs".equals(args[i])) {
				i++;
				detector.setKnownSTRsFile(args[i]);
			} else if("-embeddedSNVs".equals(args[i])) {
				detector.setCallEmbeddedSNVs(true);
			} else if("-genomeSize".equals(args[i])) {
				i++;
				detector.setInputGenomeSize(Long.parseLong(args[i]));
			} else if("-binSize".equals(args[i])) {
				i++;
				detector.setBinSize(Integer.parseInt(args[i]));
			} else if("-algCNV".equals(args[i])) {
				i++;
				detector.setAlgCNV(args[i]);
			} else if ("-maxPCTOverlapCNVs".equals(args[i])) {
				i++;
				detector.setMaxPCTOverlapCNVs(Integer.parseInt(args[i]));
			} else if("-maxLenDeletion".equals(args[i])) {
				i++;
				detector.setMaxLengthDeletion(Integer.parseInt(args[i]));
			} else if("-ignoreProperPairFlag".equals(args[i])) {
				detector.setIgnoreProperPairFlag(true);
			} else if("-minSVQuality".equals(args[i])) {
				i++;
				detector.setMinSVQuality(Short.parseShort(args[i]));
			} else if("-sizeSRSeed".equals(args[i])) {
				i++;
				detector.setSplitReadSeed(Integer.parseInt(args[i]));
			} else if("-ignoreXS".equals(args[i])) {
				System.err.println("WARN: Deprecated option -ignoreXS. Use minMQ option to control which alignments are considered unique");
			} else if("-minMQ".equals(args[i])) {
				i++;
				detector.setMinMQ(Integer.parseInt(args[i]));
			} else if("-sampleId".equals(args[i])) {
				i++;
				detector.setSampleId(args[i]);
			} else if("-psp".equals(args[i])) {
				detector.setPrintSamplePloidy(true);
			} else if("-knownVariants".equals(args[i])) {
				i++;
				detector.setKnownVariantsFile(args[i]);
			} else if("-genotypeAll".equals(args[i])) {
				detector.setGenotypeAll(true);
			} else if("-noRep".equals(args[i])) {
				detector.setFindRepeats(false);
			} else if("-noRD".equals(args[i])) {
				detector.setRunRDAnalysis(false);
			} else if("-noNewCNV".equals(args[i])) {
				detector.setFindNewCNVs(false);
			} else if("-noRP".equals(args[i])) {
				detector.setRunRPAnalysis(false);
			} else if("-noSNVS".equals(args[i])) {
				detector.setFindSNVs(false);
			} else {
				System.err.println("Unrecognized option: "+args[i]);
				CommandsDescriptor.getInstance().printHelp(VariantsDetector.class);
				return;
			}
			i++;
		}
		
		detector.referenceFile = args[i++];
		detector.alignmentsFile = args[i++];
		String outPrefix = args[i++];
		if(detector.findSNVs) {
			detector.setOutVars(new PrintStream(outPrefix+".vcf"));
		}
		if(detector.runRDAnalysis || detector.findRepeats || detector.runRPAnalysis) {
			detector.setOutStructural(new PrintStream(outPrefix+"_SV.gff"));
		}
		if(hetRate==-1 && detector.normalPloidy==1) {
			detector.setHeterozygosityRate(VariantPileupListener.DEF_HETEROZYGOSITY_RATE_HAPLOID);
		}
		detector.processAll();
	}

	
	public void setMinMQ(int minMQ) {
		mmRegsCalc.setMinMQ(minMQ);
		generator.setMinMQ(minMQ);
		rpAnalyzer.setMinMQ(minMQ);
	}
	
	public void setMinMQ(Integer minMQ) {
		this.setMinMQ(minMQ.intValue());
		
	}

	public String getReferenceFile() {
		return referenceFile;
	}

	public void setReferenceFile(String referenceFile) {
		this.referenceFile = referenceFile;
	}

	public String getAlignmentsFile() {
		return alignmentsFile;
	}

	public void setAlignmentsFile(String alignmentsFile) {
		this.alignmentsFile = alignmentsFile;
	}

	public byte getNormalPloidy() {
		return normalPloidy;
	}

	public void setNormalPloidy(byte normalPloidy) {
		this.normalPloidy = normalPloidy;
		varListener.setNormalPloidy(normalPloidy);
	}
	
	public void setNormalPloidy(Byte normalPloidy) {
		setNormalPloidy(normalPloidy.byteValue());
	}
	

	public String getKnownSVsFile() {
		return knownSVsFile;
	}

	public void setKnownSVsFile(String knownSVsFile) {
		this.knownSVsFile = knownSVsFile;
	}
	
	public String getKnownSTRsFile() {
		return knownSTRsFile;
	}

	public void setKnownSTRsFile(String knownSTRsFile) {
		this.knownSTRsFile = knownSTRsFile;
	}
	
	public void setCallEmbeddedSNVs(boolean callEmbeddedSNVs) {
		varListener.setCallEmbeddedSNVs(callEmbeddedSNVs);
	}
	
	public void setCallEmbeddedSNVs(Boolean callEmbeddedSNVs) {
		setCallEmbeddedSNVs(callEmbeddedSNVs.booleanValue());
	}

	public String getKnownVariantsFile() {
		return knownVariantsFile;
	}

	public void setKnownVariantsFile(String knownVariantsFile) {
		this.knownVariantsFile = knownVariantsFile;
	}

	public boolean isFindRepeats() {
		return findRepeats;
	}

	public void setFindRepeats(boolean findRepeats) {
		this.findRepeats = findRepeats;
	}
	
	public void setFindRepeats(Boolean findRepeats) {
		setFindRepeats(findRepeats.booleanValue());
	}

	public boolean isRunRDAnalysis() {
		return runRDAnalysis;
	}

	public void setRunRDAnalysis(boolean runRDAnalysis) {
		this.runRDAnalysis = runRDAnalysis;
	}
	
	public void setRunRDAnalysis(Boolean runRDAnalysis) {
		setRunRDAnalysis(runRDAnalysis.booleanValue());
	}

	public boolean isFindSNVs() {
		return findSNVs;
	}

	public void setFindSNVs(boolean findSNVs) {
		this.findSNVs = findSNVs;
	}
	
	public void setFindSNVs(Boolean findSNVs) {
		setFindSNVs(findSNVs.booleanValue());
	}
	
	public boolean isRunRPAnalysis() {
		return runRPAnalysis;
	}

	public void setRunRPAnalysis(boolean runRPAnalysis) {
		this.runRPAnalysis = runRPAnalysis;
	}
	
	public void setRunRPAnalysis(Boolean runRPAnalysis) {
		setRunRPAnalysis(runRPAnalysis.booleanValue());
	}
	
	public boolean isFindNewCNVs() {
		return findNewCNVs;
	}

	public void setFindNewCNVs(boolean findNewCNVs) {
		this.findNewCNVs = findNewCNVs;
	}
	
	public void setFindNewCNVs(Boolean findNewCNVs) {
		this.setFindNewCNVs(findNewCNVs.booleanValue());
	}

	public String getAlgCNV() {
		return algCNV;
	}

	public void setAlgCNV(String algCNV) {
		this.algCNV = algCNV;
	}

	public void setBinSize(int binSize) {
		this.binSize = binSize;
	}
	
	
	public int getBinSize() {
		return binSize;
	}

	public void setBinSize(Integer binSize) {
		setBinSize(binSize.intValue());
	}

	public long getInputGenomeSize() {
		return inputGenomeSize;
	}

	public void setInputGenomeSize(long inputGenomeSize) {
		this.inputGenomeSize = inputGenomeSize;
	}
	
	public void setInputGenomeSize(Long inputGenomeSize) {
		setInputGenomeSize(inputGenomeSize.longValue());
	}

	public String getSampleId() {
		return sampleId;
	}

	public void setSampleId(String sampleId) {
		this.sampleId = sampleId;
	}

	
	public boolean isPrintSamplePloidy() {
		return printSamplePloidy;
	}

	public void setPrintSamplePloidy(boolean printSamplePloidy) {
		this.printSamplePloidy = printSamplePloidy;
	}
	
	public void setPrintSamplePloidy(Boolean printSamplePloidy) {
		this.printSamplePloidy = printSamplePloidy;
	}

	public ReferenceGenome getGenome() {
		return genome;
	}

	public void setGenome(ReferenceGenome genome) {
		this.genome = genome;
	}

	public void setOutVars(PrintStream outVars) {
		this.outVars = outVars;
	}

	public void setOutStructural(PrintStream outStructural) {
		this.outStructural = outStructural;
	}
	
	public PrintStream getOutVars() {
		return outVars;
	}

	public PrintStream getOutStructural() {
		return outStructural;
	}

	public ProgressNotifier getProgressNotifier() {
		return progressNotifier;
	}

	public void setProgressNotifier(ProgressNotifier progressNotifier) {
		this.progressNotifier = progressNotifier;
	}

	public void setQuerySeq(String querySeq) {
		generator.setQuerySeq(querySeq);
	}

	public void setQueryFirst(int queryFirst) {
		generator.setQueryFirst(queryFirst);
	}
	
	public void setQueryFirst(Integer queryFirst) {
		setQueryFirst(queryFirst.intValue());
	}

	public void setQueryLast(int queryLast) {
		generator.setQueryLast(queryLast);
	}
	
	public void setQueryLast(Integer queryLast) {
		setQueryLast(queryLast.intValue());
	}


	public void setHeterozygosityRate(double heterozygosityRate) {
		varListener.setHeterozygosityRate(heterozygosityRate);
	}
	
	public void setHeterozygosityRate(Double heterozygosityRate) {
		setHeterozygosityRate(heterozygosityRate.doubleValue());
	}

	public void setMaxBaseQS(short maxBaseQS) {
		varListener.setMaxBaseQS(maxBaseQS);
	}
	
	public void setMaxBaseQS(Short maxBaseQS) {
		setMaxBaseQS(maxBaseQS.shortValue());
	}

	public void setIgnoreLowerCaseRef(boolean ignoreLowerCaseRef) {
		varListener.setIgnoreLowerCaseRef(ignoreLowerCaseRef);
	}
	
	public void setIgnoreLowerCaseRef(Boolean ignoreLowerCaseRef) {
		setIgnoreLowerCaseRef(ignoreLowerCaseRef.booleanValue());
	}

	public void setMaxAlnsPerStartPos(int maxAlnsPerStartPos) {
		generator.setMaxAlnsPerStartPos(maxAlnsPerStartPos);
	}
	
	public void setMaxAlnsPerStartPos(Integer maxAlnsPerStartPos) {
		setMaxAlnsPerStartPos(maxAlnsPerStartPos.intValue());
	}
	
	public boolean isProcessOnlyUniqueAlignments() {
		return generator.isProcessOnlyUniqueAlignments();
	}

	public void setProcessOnlyUniqueAlignments(boolean processOnlyUniqueAlignments) {
		generator.setProcessOnlyUniqueAlignments(processOnlyUniqueAlignments);
	}
	
	public void setProcessOnlyUniqueAlignments(Boolean processOnlyUniqueAlignments) {
		setProcessOnlyUniqueAlignments(processOnlyUniqueAlignments.booleanValue());
	}
	
	public boolean isProcessSecondaryAlignments() {
		return generator.isProcessSecondaryAlignments();
	}

	public void setProcessSecondaryAlignments(boolean processSecondaryAlignments) {
		generator.setProcessSecondaryAlignments(processSecondaryAlignments);
	}
	
	public void setProcessSecondaryAlignments(Boolean processSecondaryAlignments) {
		setProcessSecondaryAlignments(processSecondaryAlignments.booleanValue());
	}
	
	

	public byte getBasesToIgnore5P() {
		return generator.getBasesToIgnore5P();
	}

	public void setBasesToIgnore5P(byte basesToIgnore5P) {
		generator.setBasesToIgnore5P(basesToIgnore5P);
	}
	
	public void setBasesToIgnore5P(Byte basesToIgnore5P) {
		setBasesToIgnore5P(basesToIgnore5P.byteValue());
	}

	public byte getBasesToIgnore3P() {
		return generator.getBasesToIgnore3P();
	}

	public void setBasesToIgnore3P(byte basesToIgnore3P) {
		generator.setBasesToIgnore3P(basesToIgnore3P);
	}
	
	public void setBasesToIgnore3P(Byte basesToIgnore3P) {
		setBasesToIgnore3P(basesToIgnore3P.byteValue());
	}

	public void setGenotypeAll(boolean genotypeAll) {
		varListener.setGenotypeAll(true);
	}
	
	public void setGenotypeAll(Boolean genotypeAll) {
		setGenotypeAll(genotypeAll.booleanValue());
	}

	public void setMaxAltCoverage(int maxAltCoverage) {
		varListener.setMaxAltCoverage(maxAltCoverage);
	}
	
	public void setMaxAltCoverage(Integer maxAltCoverage) {
		if(maxAltCoverage!=null) varListener.setMaxAltCoverage(maxAltCoverage);
	}

	public void setMinAltCoverage(int minAltCoverage) {
		varListener.setMinAltCoverage(minAltCoverage);
	}
	
	public void setMinAltCoverage(Integer minAltCoverage) {
		if(minAltCoverage!=null) varListener.setMinAltCoverage(minAltCoverage);
	}
	
	public void setMinQuality (short minQuality) {
		varListener.setMinQuality(minQuality);
	}
	
	public void setMinQuality (Short minQuality) {
		setMinQuality(minQuality.shortValue());
	}
	
	public int getMaxLengthDeletion() {
		return rpAnalyzer.getMaxLengthDeletion();
	}

	public void setMaxLengthDeletion(int maxLengthDeletion) {
		rpAnalyzer.setMaxLengthDeletion(maxLengthDeletion);
	}

	public void setMaxLengthDeletion(Integer maxLengthDeletion) {
		setMaxLengthDeletion(maxLengthDeletion.intValue());
	}
	
	public short getMinSVQuality() {
		return minSVQuality;
	}

	public void setMinSVQuality(short minSVQuality) {
		this.minSVQuality = minSVQuality;
	}

	public void setMinSVQuality(Short minQuality) {
		setMinSVQuality(minQuality.shortValue());
	}
	
	

	public int getMaxPCTOverlapCNVs() {
		return maxPCTOverlapCNVs;
	}

	public void setMaxPCTOverlapCNVs(int maxPCTOverlapCNVs) {
		this.maxPCTOverlapCNVs = maxPCTOverlapCNVs;
	}
	
	public void setMaxPCTOverlapCNVs(Integer maxPCTOverlapCNVs) {
		this.setMaxPCTOverlapCNVs(maxPCTOverlapCNVs.intValue());
	}

	public int getSplitReadSeed() {
		return rpAnalyzer.getSeedSize();
	}

	public void setSplitReadSeed(int seedSize) {
		rpAnalyzer.setSeedSize(seedSize);
	}

	public void setSplitReadSeed(Integer seedSize) {
		setSplitReadSeed(seedSize.intValue());
	}
	
	public boolean isIgnoreProperPairFlag() {
		return rpAnalyzer.isIgnoreProperPairFlag();
	}

	public void setIgnoreProperPairFlag(boolean ignoreProperPairFlag) {
		rpAnalyzer.setIgnoreProperPairFlag(ignoreProperPairFlag);
	}
	
	public void setIgnoreProperPairFlag(Boolean ignoreProperPairFlag) {
		setIgnoreProperPairFlag(ignoreProperPairFlag.booleanValue());
	}

	public void processAll () throws IOException {
		if(!runRDAnalysis) findNewCNVs = false;
		printParameters();
		validateParameters();
		try {
			if(genome==null) {
				log.info("Loading reference sequence from file: "+referenceFile);
				genome = new ReferenceGenome(referenceFile);
			}
			referenceGenomeSize = genome.getTotalLength();
			log.info("Loaded "+genome.getNumSequences()+" sequences");
			if(progressNotifier!=null && !progressNotifier.keepRunning(1)) return;  
			calledSVs = new GenomicRegionSortedCollection<CalledGenomicVariant>(genome.getSequencesMetadata());
			if (knownSVsFile!=null) {
				calledSVs.addAll(svsFH.loadVariants(knownSVsFile));
				log.info("Loaded "+calledSVs.size()+" input SVs");
			}
			if(findRepeats) {
				log.info("Finding repeats using reads with multiple alignments");
				List<CalledCNV> multipleMCnvs = mmRegsCalc.calculateMultipleMappingRegions(alignmentsFile);
				log.info("Found "+multipleMCnvs.size()+" repeats");
				calledSVs.addAll(multipleMCnvs);
				log.info("Number of SVs after finding repeats: "+calledSVs.size());
			}
			if(progressNotifier!=null && !progressNotifier.keepRunning(4)) return;
			//Call CNVs based on read depth
			if(runRDAnalysis) {
				log.info("Running read depth (RD) analysis to identify/genotype CNVs");
				List<CalledCNV> cnvsRD = runRDAnalysis();
				if(cnvsRD !=null) {
					log.info("Found "+cnvsRD.size()+" new CNVs running the RD analysis");
					calledSVs.addAll(cnvsRD);
				}
				log.info("Total number of SVs: "+calledSVs.size());
			}
			if(progressNotifier!=null && !progressNotifier.keepRunning(10)) return;
			if(runRPAnalysis) {
				log.info("Running read pair (RP) analysis to identify indels and inversions");
				List<CalledGenomicVariant> svsRP = runRPAnalysis(); 
				log.info("Found "+svsRP.size()+" new structural variants running the RP analysis");
				calledSVs.addAll(svsRP);
				log.info("Total number of SVs: "+calledSVs.size());
			}
			if(progressNotifier!=null && !progressNotifier.keepRunning(15)) return;
			if(findSNVs) {
				findSNVS();
			}
			if(outStructural!=null) {
				log.info("Saving structural variants");
				GFFVariantsFileHandler svHandler = new GFFVariantsFileHandler();
				svHandler.saveVariants(calledSVs.asList(), outStructural);
			}
			log.info("Variants Detector Completed");
		} finally {
			if(outVars!=null) outVars.close();
			if(outStructural!=null) outStructural.close();
			dispose();
		}
	}


	public void printParameters() {
		log.info("Reference file: "+referenceFile);
		log.info("Alignments file: "+alignmentsFile);
		log.info("Heterozygocity rate: "+varListener.getHeterozygosityRate());
		if(generator.getQuerySeq()!=null) {
			log.info("Analyze only region at "+generator.getQuerySeq()+":"+generator.getQueryFirst()+"-"+generator.getQueryLast());
		}
		log.info("Minimum Coverage Alternative Allele: "+varListener.getMinAltCoverage());
		log.info("Maximum Coverage Alternative Allele: "+varListener.getMaxAltCoverage());
		log.info("Minimum genotype quality score (PHRED): "+varListener.getMinQuality());
		log.info("Maximum base quality score (PHRED): "+varListener.getMaxBaseQS());
		log.info("Maximum number of alignments starting at the same position: "+generator.getMaxAlnsPerStartPos());
		log.info("Ignore variants in lower case reference positions: "+varListener.isIgnoreLowerCaseRef());
		log.info("Process secondary alignments for SNV detection: "+generator.isProcessSecondaryAlignments());
		log.info("Bases to ignore in the 5' end: "+generator.getBasesToIgnore5P());
		log.info("Bases to ignore in the 3' end: "+generator.getBasesToIgnore3P());
		log.info("Genotype all sites in the genome covered by at least one read: "+varListener.isGenotypeAll());
		log.info("Normal ploidy: "+normalPloidy);
		log.info("Print header with sample ploidy in the vcf file: "+printSamplePloidy);
		
		log.info("Bin size: "+getBinSize());
		log.info("Input genome size: "+getInputGenomeSize());
		log.info("Min quality for structural variants (PHRED) : "+getMinSVQuality());
		log.info("Algorithms for RD analysis: "+getAlgCNV());
		log.info("Max percentage of overlap between input CNVs and new CNVs: "+getMaxPCTOverlapCNVs());
		log.info("Max length of deletions found with RP analysis : "+getMaxLengthDeletion());
		log.info("Ignore proper pair flag for RP analysis : "+isIgnoreProperPairFlag());
		log.info("Size of the seed for split-read alignments : "+getSplitReadSeed()); 
		log.info("Minimum mapping quality to consider an alignment unique: "+mmRegsCalc.getMinMQ());
		
		log.info("Sample id: "+sampleId);
		
		
		log.info("File with known structural variants: "+knownSVsFile);
		log.info("File with known short tandem repeats: "+knownSTRsFile);
		log.info("File with known Variants: "+knownVariantsFile);
		log.info("Find repeats using reads with multiple alignments: "+findRepeats);
		log.info("Run RD analysis to genotype given SVs and find new CNVs: "+runRDAnalysis);
		log.info("Identify new CNVs using the RD data: "+findNewCNVs);
		log.info("Run RP analysis to find indels and inversions: "+runRPAnalysis);
		
		log.info("Find SNVs: "+findSNVs);
		
		
	}
	private void validateParameters () throws IOException {
		if(referenceFile == null && genome==null) {
			throw new IOException("A reference file is required");
		}
	}
	
	public List<CalledCNV> runRDAnalysis() throws IOException {
		log.info("Loading bins");
		ReadDepthDistribution rdDistribution = new ReadDepthDistribution(genome, binSize);
		log.info("Loaded bins. Assembly genome size: "+rdDistribution.getGenomeSize());
		//Pass parameters
		rdDistribution.setLog(this.getLog());
		rdDistribution.setMinMQ(generator.getMinMQ());
		
		
		log.info("Processing alignments file: "+alignmentsFile);
		rdDistribution.processAlignments(alignmentsFile);
		log.info("Processed alignments file: "+alignmentsFile);
		if(progressNotifier!=null && !progressNotifier.keepRunning(7)) return new ArrayList<CalledCNV>();
		rdDistribution.correctDepthByGCContent();
		log.info("Corrected GCContent biases");
		log.info("Calculating read depth parameters");
		rdDistribution.calculateReadDepthDistParameters();
		log.info("Calculated read depth parameters. Mean read depth: "+rdDistribution.getMeanReadDepth()+". Standard deviation: "+rdDistribution.getSigmaReadDepth());
		GenomicRegionSortedCollection<CalledCNV> inputCNVs = new GenomicRegionSortedCollection<CalledCNV>();
		if(calledSVs!=null) {
			log.info("Calculating normalized read depth for input repeats and CNVs");
			inputCNVs = selectCalledCNVs(calledSVs);
			calculateNormalizedAverageDepth(rdDistribution,inputCNVs);
			updateDelDupStatus(inputCNVs.asList());
			log.info("Calculated normalized read depth for input CNVs and repeats");
		}
		if(findNewCNVs) {
			List<CalledCNV> cnvs = new ArrayList<CalledCNV>();
			
			String[] algs = algCNV.split(",");
			for (String algorithm : algs){
				SingleSampleReadDepthAlgorithm algor;
				try {
					algor = (SingleSampleReadDepthAlgorithm) Class.forName("ngsep.discovery.rd."+algorithm+"ReadDepthAlgorithm").newInstance();
				} catch (Exception e) {
					throw new IOException("Unrecognized algorithm "+algorithm+" for read depth analysis", e);
				} 
				List<CalledCNV> cnvsA = executeCNValgorithm(algor, rdDistribution);
				cnvs.addAll(filterCNVs(cnvsA,inputCNVs));
			}
			updateDelDupStatus(cnvs);
			return cnvs;
		}
		return null;
	}
	
	

	private void updateDelDupStatus(List<CalledCNV> cnvs) {
		for(CalledCNV cnv:cnvs) {
			if(cnv.getNumCopies()<normalPloidy) cnv.setTextGenotype(CalledCNV.TEXT_GEN_DEL);
		}
		
	}


	private List<CalledCNV> executeCNValgorithm(SingleSampleReadDepthAlgorithm algorithm, ReadDepthDistribution rdDistribution){
		SingleSampleReadDepthAlgorithm rdAlgorithm = algorithm;
		rdAlgorithm.setLog(this.getLog());
		if(inputGenomeSize>0) rdAlgorithm.setGenomeSize(inputGenomeSize);
		else rdAlgorithm.setGenomeSize(rdDistribution.getGenomeSize());
		rdAlgorithm.setNormalPloidy(normalPloidy);
		rdAlgorithm.setReadDepthDistribution(rdDistribution);
		return rdAlgorithm.callCNVs();
	}
	
	private List<CalledCNV> filterCNVs(List<CalledCNV> cnvs,GenomicRegionSortedCollection<CalledCNV> inputCNVs) {
		List<CalledCNV> answer = new ArrayList<CalledCNV>();
		
		for(CalledCNV cnv:cnvs) {
			if(cnv.getGenotypeQuality()<minSVQuality) continue;
			if(maxPCTOverlapCNVs<100) {
				GenomicRegionSortedCollection<CalledCNV> spanningCNVs = inputCNVs.findSpanningRegions(cnv);
				double maxSpan = 0;
				for(CalledCNV c2:spanningCNVs) {
					int nextSpan = GenomicRegionSpanComparator.getInstance().getSpanLength(cnv.getFirst(), cnv.getLast(), c2.getFirst(), c2.getLast());
					if(maxSpan<nextSpan) maxSpan= nextSpan;
				}
				double l = cnv.length();
				if(100.0*maxSpan/l > maxPCTOverlapCNVs) continue;
			}
			answer.add(cnv);
		}
		return answer;
	}
	private GenomicRegionSortedCollection<CalledCNV> selectCalledCNVs(GenomicRegionSortedCollection<CalledGenomicVariant> svs) {
		return selectCalledCNVs(svs,false);
	}
	private GenomicRegionSortedCollection<CalledCNV> selectCalledCNVs(GenomicRegionSortedCollection<CalledGenomicVariant> svs, boolean onlyRepeats) {
		GenomicRegionSortedCollection<CalledCNV> calledCNVs = new GenomicRegionSortedCollection<CalledCNV>(svs.getSequenceNames());
		for(CalledGenomicVariant sv:svs) {
			if(sv instanceof CalledCNV && (!onlyRepeats || sv.getType()==GenomicVariant.TYPE_REPEAT)) {
				calledCNVs.add((CalledCNV)sv);
			}
		}
		return calledCNVs;
	}

	public void calculateNormalizedAverageDepth(ReadDepthDistribution rdDistribution, GenomicRegionSortedCollection<CalledCNV> calledCNVs) {
		int binSize = rdDistribution.getBinSize();
		for(String seqName:calledCNVs.getSequenceNames().getNamesStringList()) {
			List<CalledCNV> seqCNVs = calledCNVs.getSequenceRegions(seqName).asList();
			
			List<ReadDepthBin> seqBins = rdDistribution.getBins(seqName);
			if(seqBins!=null) {
				for(CalledCNV cnv:seqCNVs) {
					int binStart = (cnv.getFirst()-1)/binSize;
					int binEnd = (cnv.getLast()-1)/binSize;
					double avg = 0;
					int sumUncorrected = 0;
					int nBins = 0;
					for(int i=binStart;i<seqBins.size()&& i<=binEnd;i++) {
						ReadDepthBin bin = seqBins.get(i);
						sumUncorrected += bin.getRawReadDepth();
						avg+=bin.getCorrectedReadDepth();
						nBins++;
					}
					//TODO: Update genotype quality
					if(nBins ==0) cnv.setNumCopies(normalPloidy,true);
					else {
						cnv.setTotalReadDepth(sumUncorrected);
						avg/=nBins;
						cnv.setNumCopies((float) (avg*normalPloidy/rdDistribution.getMeanReadDepth()),true);
					}
				}
			} else {
				log.warning("Sequence name: "+seqName+" in input CNVs not found in the genome");
			}
		}
	}

	private GenomicRegionSortedCollection<GenomicVariant> makeNonRedundantSTRs( List<GenomicRegion> strs) {
		QualifiedSequenceList sequences = genome.getSequencesMetadata();
		GenomicRegionSortedCollection<GenomicRegion> strsC = new GenomicRegionSortedCollection<GenomicRegion>(sequences);
		strsC.addAll(strs);
		GenomicRegionSortedCollection<GenomicVariant> answer = new GenomicRegionSortedCollection<GenomicVariant>(sequences);
		
		for(QualifiedSequence seq:sequences) {
			String seqName = seq.getName();
			int first = 0;
			int last = 0;
			GenomicRegionSortedCollection<GenomicRegion> seqSTRs = strsC.getSequenceRegions(seqName);
			for(GenomicRegion r:seqSTRs) {
				if(last == 0 || !mergeSTRs(seqName,first,last,r)) {
					if(last>0) {
						GenomicVariant nextVar = makeSTRVariant(seqName, Math.max(1, first-1),Math.min(last+1,seq.getLength()));
						if(nextVar!=null) answer.add(nextVar);
					}
					
					first = r.getFirst();
				}
				last = r.getLast();
			}
			if(last>0) {
				GenomicVariant nextVar = makeSTRVariant(seqName, Math.max(1, first-1),Math.min(last+1,seq.getLength()));
				if(nextVar!=null) answer.add(nextVar);
			}
		}
		
		return answer;
	}
	private boolean mergeSTRs(String sequenceName, int first, int last, GenomicRegion r) {
		if(r.getFirst()>last+5) return false;
		else if (last-r.getFirst()>0) return true;
		CharSequence ref1 = genome.getReference(sequenceName, Math.max(first, last-10), last);
		CharSequence ref2 = genome.getReference(sequenceName, r.getFirst(), r.getLast());
		if(ref1==null || ref2==null) return false;
		return AbstractLimitedSequence.getOverlapLength(ref1, ref2)>5;
	}


	private GenomicVariant makeSTRVariant(String sequenceName, int first, int last) {
		List<String> alleles = new ArrayList<String>();
		CharSequence reference = genome.getReference(sequenceName, first, last);
		if(reference==null) {
			log.warning("Reference not found for input STR at coordinates "+sequenceName+":"+first+"-"+last);
			return null;
		}
		alleles.add(reference.toString());
		GenomicVariantImpl answer = new GenomicVariantImpl(sequenceName, first, alleles);
		answer.setType(GenomicVariant.TYPE_STR);
		return answer;
	}

	public void findSNVS() throws IOException {
		if(knownVariantsFile!=null) {
			log.info("Loading input variants");
			List<GenomicVariant> knownVariants = VCFFileReader.loadVariants(knownVariantsFile,true);
			log.info("Loaded "+knownVariants.size()+" input variants");
			varListener.setInputVariants(knownVariants);
			//TODO: Choose list or collection better
			GenomicRegionSortedCollection<GenomicVariant> knownVarsC = new GenomicRegionSortedCollection<GenomicVariant>();
			knownVarsC.addAll(knownVariants);
			indelRealigner.setInputVariants(knownVarsC);
		} else if(knownSTRsFile!=null) {
			log.info("Loading input short tandem repeats");
			//TODO: Choose the best format
			SimpleGenomicRegionFileHandler rfh = new SimpleGenomicRegionFileHandler();
			List<GenomicRegion> strs = rfh.loadRegions(knownSTRsFile);
			indelRealigner.setInputVariants(makeNonRedundantSTRs(strs));
			log.info("Loaded "+strs.size()+" input short tandem repeats");
		}
		log.info("Finding variants");
		if(outVars!=null) {
			header = VCFFileHeader.makeDefaultEmptyHeader();
			Sample s = new Sample(sampleId);
			s.setNormalPloidy(normalPloidy);
			header.addSample(s, printSamplePloidy);
			varsFW.printHeader(header,outVars);
		}
		else calledVars.clear();
		indelRealigner.setGenome(genome);
		generator.addListener(indelRealigner);
		varListener.clear();
		varListener.setGenome(genome);
		generator.addListener(varListener);
		generator.addListener(this);
		generator.processFile(alignmentsFile);
	}

	private void saveSequenceVariants(String sequenceName) {
		List<CalledCNV> sequenceCNVs= selectCalledCNVs(calledSVs.getSequenceRegions(sequenceName)).asList();
		List<CalledGenomicVariant> sequenceVariants = varListener.getCalledVariants();
		boolean [] varInCNV = new boolean [sequenceVariants.size()]; 
		intersectVariantsCNVs(sequenceCNVs,sequenceVariants,varInCNV);
		if(outVars!=null) {
			for(int i=0;i<sequenceVariants.size();i++) {
				CalledGenomicVariant call = sequenceVariants.get(i);
				int [] format;
				if(call instanceof CalledSNV) format = VCFRecord.DEF_FORMAT_ARRAY_NGSEP_SNV;
				else if (call.getAllCounts()!=null) format = VCFRecord.DEF_FORMAT_ARRAY_NGSEP_SNV;
				else format = VCFRecord.DEF_FORMAT_ARRAY_NGSEP_NOSNV;
				VCFRecord record = new VCFRecord(call, format, call, header);
				if(varInCNV[i]) record.addAnnotation(new GenomicVariantAnnotation(call, GenomicVariantAnnotation.ATTRIBUTE_IN_CNV, "1"));
				varsFW.printVCFRecord(record, outVars);
			}
			outVars.flush();
		} else {
			calledVars.addAll(sequenceVariants);
		}
		varListener.clear();
	}
	private void intersectVariantsCNVs(List<CalledCNV> sequenceCNVs,List<CalledGenomicVariant> sequenceVars, boolean [] varInCNV) {
		int threshold = getBinSize();
		int indexFirst = 0;
		for(int i=0;i<sequenceVars.size();i++) {
			CalledGenomicVariant var = sequenceVars.get(i);
			//Clean up old estimate
			varInCNV[i] = false;
			var.updateAllelesCopyNumberFromCounts(normalPloidy);
			int first = Math.max(0, var.getFirst()-threshold);
			//Discard CNVs ending before start
			while(indexFirst<sequenceCNVs.size()) {
				CalledCNV cnv = sequenceCNVs.get(indexFirst);
				if(cnv.getLast() < first) {
					indexFirst++;
				} else {
					break;
				}
			}
			if(indexFirst==sequenceCNVs.size()) continue;
			for(int j=indexFirst;j<sequenceCNVs.size();j++) {
				CalledCNV cnv = sequenceCNVs.get(j);
				int startVicinity = cnv.getFirst()-threshold;
				int endVicinity = cnv.getLast()+threshold;
				boolean closeToCNV = startVicinity <= var.getLast() && var.getFirst() <= endVicinity;
				//boolean inCNV = cnv.getStart() <= var.getEnd() && var.getStart() <= cnv.getEnd();
				//double normalizedCoverage = (var.getCountReference()+var.getCountAlternative())/cnvListener.getHaploidAverageCoverage();
				//if(inCNV || (closeToCNV && Math.abs(normalizedCoverage-normalPloidy)>0.5)) {
				if(closeToCNV) {
					varInCNV[i] = true;
					byte numCopies = (byte)Math.round(cnv.getNumCopies());
					var.updateAllelesCopyNumberFromCounts(numCopies);
					if(var.isHeterozygous()) {
						cnv.addHeterozygousVariant();
					}
				} else if (cnv.getFirst() > var.getLast()+threshold) {
					break;
				}
			}
		}
		
	}

	@Override
	public void onPileup(PileupRecord pileup) {
		coveredGenomeSize++;
		if(progressNotifier!=null && coveredGenomeSize%10000==0) {
			int progress = 15+(int)Math.round(85.0*coveredGenomeSize/referenceGenomeSize);
			generator.setKeepRunning(progressNotifier.keepRunning(progress));
		}
	}

	
	@Override
	public void onSequenceStart(QualifiedSequence sequence) {
	}

	@Override
	public void onSequenceEnd(QualifiedSequence sequence) {
		saveSequenceVariants(sequence.getName());
	}
	
	private List<CalledGenomicVariant> runRPAnalysis() throws IOException {
		GenomicRegionSortedCollection<CalledCNV> duplications = new GenomicRegionSortedCollection<CalledCNV>(genome.getSequencesMetadata());
		GenomicRegionSortedCollection<CalledCNV> calledCNVs = selectCalledCNVs(calledSVs);
		for(CalledCNV cnv:calledCNVs) {
			if(cnv.getNumCopies()>1.25*normalPloidy) duplications.add(cnv);
		}
		log.info("Using "+duplications.size()+" duplications out of "+calledCNVs.size()+" svs in the read pair algorithm");
		rpAnalyzer.setReference(genome);
		rpAnalyzer.setDuplications(duplications);
		List<CalledGenomicVariant> svsRP = rpAnalyzer.findVariants(alignmentsFile);
		log.info("Identified "+svsRP.size()+" candidate structural variants using the read pair algorithm. Filtering by quality score");
		svsRP = filterSVsReadPair(svsRP);
		for(CalledCNV cnv:duplications) {
			if(cnv.getTandemFragments()>1 && cnv.getTandemFragments()>3*cnv.getTransDupFragments()) cnv.setTextGenotype(CalledCNV.TEXT_GEN_TANDEMDUP);
			else if(cnv.getTransDupFragments()>1 && cnv.getTransDupFragments()>3*cnv.getTandemFragments()) cnv.setTextGenotype(CalledCNV.TEXT_GEN_TRANSDUP);
		}
		return svsRP;
	}
	
	private List<CalledGenomicVariant> filterSVsReadPair(List<CalledGenomicVariant> svs) {
		List<CalledGenomicVariant> answer = new ArrayList<CalledGenomicVariant>();
		for(CalledGenomicVariant v:svs) {
			if(v.getGenotypeQuality()>=minSVQuality) answer.add(v);
		}
		return answer;
	}

	public Logger getLog() {
		return log;
	}

	public void setLog(Logger log) {
		this.log = log;
		generator.setLog(log);
		rpAnalyzer.setLog(log);
	}

	public GenomicRegionSortedCollection<CalledGenomicVariant> getCalledSVs() {
		return calledSVs;
	}

	public List<CalledGenomicVariant> getCalledVars() {
		return calledVars;
	}
	/**
	 * Removes heavy resources loaded in memory during the process
	 */
	private void dispose() {
		generator.setInputVariants(null);
		indelRealigner.setInputVariants(null);
		varListener.setInputVariants(null);
		varListener.clear();
		
		
	}
}
