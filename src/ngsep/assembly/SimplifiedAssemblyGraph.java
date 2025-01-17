package ngsep.assembly;

import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.Serializable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.Map.Entry;

public class SimplifiedAssemblyGraph implements Serializable {
	private static final long serialVersionUID = 1L;

	private List<CharSequence> sequences;
	private Map<Integer, Map<Integer, Alignment>> edges;
	private Map<Integer, Map<Integer, Embedded>> embbeded;
	private Set<Integer> isEmbbeded;

	public SimplifiedAssemblyGraph(List<CharSequence> sequences) {
		this.sequences = sequences;
		edges = new HashMap<Integer, Map<Integer, Alignment>>();
		embbeded = new HashMap<Integer, Map<Integer, Embedded>>();
		isEmbbeded = new HashSet<Integer>();
		for (int i = 0; i < sequences.size(); i++)
			addEdge(i << 1, (i << 1) + 1, sequences.get(i).length(), 1);
	}

	public void printInfo() {
		System.out.println("Embedded: " + amuontOfEmbeddedSequences());
		System.out.println("Vertex: " + edges.values().size());
		int sum = 0;
		for (Map<Integer, Alignment> aln : edges.values())
			sum += aln.size();
		System.out.println("Edges: " + sum);
	}

	public int amuontOfEmbeddedSequences() {
		return isEmbbeded.size();
	}

	public boolean isEmbedded(int id) {
		return this.isEmbbeded.contains(id);
	}

	public void addEmbedded(int id, int idEmb, int pos, boolean reversed, double rate) {
		isEmbbeded.add(idEmb);
		embbeded.computeIfAbsent(id, (x) -> new HashMap<>()).put(idEmb, new Embedded(pos, reversed, rate));
	}

	public void addEdge(int id, int id2, int weigth, double rate) {
		Alignment aln = new Alignment(weigth, rate);
		edges.computeIfAbsent(id, (x) -> new HashMap<>()).put(id2, aln);
		edges.computeIfAbsent(id2, (x) -> new HashMap<>()).put(id, aln);
	}

	public void removeAllEmbeddedsIntoGraph() {
		Iterator<Integer> iter = edges.keySet().iterator();
		while (iter.hasNext())
			if (isEmbedded(iter.next() >> 1))
				iter.remove();

		for (Map<Integer, Alignment> subMap : edges.values()) {
			iter = subMap.keySet().iterator();
			while (iter.hasNext())
				if (isEmbedded(iter.next() >> 1))
					iter.remove();
		}
	}

	public void removeDuplicatedEmbeddes() {
		Iterator<Integer> iter = embbeded.keySet().iterator();
		while (iter.hasNext())
			if (isEmbedded(iter.next()))
				iter.remove();

		Set<Integer> in = new HashSet<Integer>();
		for (Map<Integer, Embedded> subMap : embbeded.values()) {
			iter = subMap.keySet().iterator();
			while (iter.hasNext()) {
				int id = iter.next();
				if (in.contains(id)) {
					iter.remove();
					continue;
				}
				in.add(id);
			}
		}
	}

	public AssemblyGraph getAssemblyGraph() {
		Queue<Integer> queue = new PriorityQueue<Integer>((Integer a, Integer b) -> a - b);
		int[] map = new int[sequences.size()];
		Arrays.fill(map, 1);

		System.out.println(this.isEmbbeded.size());
		for (int i : isEmbbeded) {
			queue.add(i);
			map[i] = 0;
		}

		map[0]--;
		cumulativeSum(map);
		List<CharSequence> list = sequencesWithoutEmbedded(queue);

		AssemblyGraph assemblyGraph = new AssemblyGraph(list);
		for (Entry<Integer, Map<Integer, Embedded>> entry : embbeded.entrySet()) {
			int parentId = entry.getKey();
			for (Entry<Integer, Embedded> entry2 : entry.getValue().entrySet()) {
				Embedded emb = entry2.getValue();
				int embeddedId = entry2.getKey();
				AssemblyEmbedded embedded = new AssemblyEmbedded(sequences.get(embeddedId), emb.getPos(),
						emb.isReversed());

				if (sequences.get(embeddedId).length() + emb.getPos() > list.get(map[parentId]).length()) {
					System.out.println("ERRORPREV!!");
				}

				assemblyGraph.addEmbedded(map[parentId], embedded);
			}
		}

		for (Entry<Integer, Map<Integer, Alignment>> entry : edges.entrySet()) {
			int v1 = entry.getKey();
			for (Entry<Integer, Alignment> entry2 : entry.getValue().entrySet()) {
				int v2 = entry2.getKey();
				if (v1 < v2) {
					Alignment alg = entry2.getValue();
					AssemblyVertex vertex1 = assemblyGraph.getVertex(map[v1 >> 1], (v1 & 1) == 0);
					AssemblyVertex vertex2 = assemblyGraph.getVertex(map[v2 >> 1], (v2 & 1) == 0);
					assemblyGraph.addEdge(vertex1, vertex2, alg.getOverlap());
				}
			}
		}

		int cunt = 0;
		for (int i = 0; i < assemblyGraph.getSequences().size(); i++) {
			CharSequence ref = assemblyGraph.getSequences().get(i);
			List<AssemblyEmbedded> a = assemblyGraph.getEmbedded(i);
			if (a == null)
				continue;
			for (AssemblyEmbedded ae : a) {
				CharSequence emb = ae.getRead();
				if (ae.getStartPosition() + emb.length() > ref.length()) {
					cunt++;
					System.out.println("error  " + i + "  " + emb.length() + "   " + ae.getStartPosition());
				}
			}
		}
		System.out.println("Test finalizado: " + cunt);

		return assemblyGraph;
	}

	/**
	 * @param queue priority queue (sorted) with embedded sequences positions
	 * @return a stable list without embedded sequences
	 */
	private List<CharSequence> sequencesWithoutEmbedded(Queue<Integer> queue) {
		List<CharSequence> list = new LinkedList<CharSequence>();

		int i = 0;
		Integer t;
		while (!queue.isEmpty()) {
			t = queue.poll();
			while (i < t && i < sequences.size()) {
				list.add(sequences.get(i));
				i++;
			}
			i++;
		}
		while (i < sequences.size()) {
			list.add(sequences.get(i));
			i++;
		}
		return list;
	}

	private void cumulativeSum(int[] map) {
		for (int i = 1; i < map.length; i++)
			map[i] += map[i - 1];
	}

	@SuppressWarnings("unchecked")
	public SimplifiedAssemblyGraph(String path) throws FileNotFoundException, IOException, ClassNotFoundException {
		try (ObjectInputStream ois = new ObjectInputStream(new FileInputStream(path))) {
			sequences = (List<CharSequence>) ois.readObject();
			edges = (Map<Integer, Map<Integer, Alignment>>) ois.readObject();
			embbeded = (Map<Integer, Map<Integer, Embedded>>) ois.readObject();
			isEmbbeded = (Set<Integer>) ois.readObject();
		}
	}

	public void save(String path) throws FileNotFoundException, IOException {
		try (ObjectOutputStream obs = new ObjectOutputStream(new FileOutputStream(path))) {
			obs.writeObject(sequences);
			obs.writeObject(edges);
			obs.writeObject(embbeded);
			obs.writeObject(isEmbbeded);
			obs.flush();
		}
	}

	public List<CharSequence> getSequences() {
		return sequences;
	}

	public void setSequences(List<CharSequence> sequences) {
		this.sequences = sequences;
	}

	public Map<Integer, Map<Integer, Alignment>> getEdges() {
		return edges;
	}

	public void setEdges(Map<Integer, Map<Integer, Alignment>> edges) {
		this.edges = edges;
	}

	public Map<Integer, Map<Integer, Embedded>> getEmbbeded() {
		return embbeded;
	}

	public void setEmbbeded(Map<Integer, Map<Integer, Embedded>> embbeded) {
		this.embbeded = embbeded;
	}

	public Set<Integer> getIsEmbbeded() {
		return isEmbbeded;
	}

	public void setIsEmbbeded(Set<Integer> isEmbbeded) {
		this.isEmbbeded = isEmbbeded;
	}

}

class Alignment implements Serializable {
	private static final long serialVersionUID = 1L;

	private int overlap;
	private double rate;

	public Alignment(int overlap, double rate) {
		this.overlap = overlap;
		this.rate = rate;
	}

	public int getOverlap() {
		return overlap;
	}

	public void setOverlap(int overlap) {
		this.overlap = overlap;
	}

	public double getRate() {
		return rate;
	}

	public void setRate(double rate) {
		this.rate = rate;
	}
}

class Embedded implements Serializable {
	private static final long serialVersionUID = 1L;

	private int pos;
	private boolean reversed;
	private double rate;

	public Embedded(int pos, boolean reversed, double rate) {
		this.pos = pos;
		this.reversed = reversed;
		this.rate = rate;
	}

	public int getPos() {
		return pos;
	}

	public void setPos(int pos) {
		this.pos = pos;
	}

	public boolean isReversed() {
		return reversed;
	}

	public void setReversed(boolean reversed) {
		this.reversed = reversed;
	}

	public double getRate() {
		return rate;
	}

	public void setRate(double rate) {
		this.rate = rate;
	}

}