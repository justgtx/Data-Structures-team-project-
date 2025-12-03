/*
SORTED CHUNK + SHORT[] IDS
- Use sorted chunk for cache building (no object explosion)
- Use short[] instead of String[] for topSuggestions
- Query IDs are just the index in the sorted array!
- No HashMap needed for ID mapping!
*/

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

public class QuerySidekick
{
	String[] guesses = new String[5];
	private RadixNode root;
	private String currentPrefix;
	
	private static final double FREQUENCY_EXPONENT = 1.0;
	
	// Query pool - persists after buildCache for lookups
	private String[] queryPool;
	private short[] queryFrequencies;
	
	// Reusable arrays for finding top 5
	private final short[] tempTop5Ids = new short[5];
	private final double[] tempScores = new double[5];
	private final int[] tempFreqs = new int[5];
	
	private abstract class RadixNode {
		short[] topSuggestionIds;  // short[] instead of String[]!
		
		abstract RadixEdge findChild(char ch);
		abstract RadixNode addChildAndGetNode(char ch, RadixEdge edge);
		
		void copyDataTo(RadixNode other) {
			other.topSuggestionIds = this.topSuggestionIds;
		}
	}
	
	private class LeafNode extends RadixNode {
		@Override RadixEdge findChild(char ch) { return null; }
		@Override RadixNode addChildAndGetNode(char ch, RadixEdge edge) {
			ChainNode n = new ChainNode(); copyDataTo(n);
			n.childChar = ch; n.childEdge = edge; return n;
		}
	}
	
	private class ChainNode extends RadixNode {
		char childChar; RadixEdge childEdge;
		@Override RadixEdge findChild(char ch) { return (childChar == ch) ? childEdge : null; }
		@Override RadixNode addChildAndGetNode(char ch, RadixEdge edge) {
			BranchNode n = new BranchNode(); copyDataTo(n);
			n.childChars = new char[]{childChar, ch};
			n.childEdges = new RadixEdge[]{childEdge, edge}; return n;
		}
	}
	
	private class BranchNode extends RadixNode {
		char[] childChars; RadixEdge[] childEdges;
		@Override RadixEdge findChild(char ch) {
			for (int i = 0; i < childChars.length; i++)
				if (childChars[i] == ch) return childEdges[i];
			return null;
		}
		@Override RadixNode addChildAndGetNode(char ch, RadixEdge edge) {
			int len = childChars.length;
			childChars = Arrays.copyOf(childChars, len + 1);
			childEdges = Arrays.copyOf(childEdges, len + 1);
			childChars[len] = ch; childEdges[len] = edge; return this;
		}
	}
	
	private class RadixEdge {
		String label; RadixNode node;
		RadixEdge(String label, RadixNode node) { this.label = label; this.node = node; }
	}
	
	private RadixEdge rootEdge;
	
	public QuerySidekick() {
		root = new LeafNode();
		rootEdge = new RadixEdge("", root);
		currentPrefix = "";
	}
	
	private void insertQuery(String query, Map<String, Integer> freqMap) {
		if (query == null || query.isEmpty()) return;
		query = query.toLowerCase();
		freqMap.put(query, freqMap.getOrDefault(query, 0) + 1);
		insertRadix(rootEdge, query, 0);
		root = rootEdge.node;
	}
	
	private void insertRadix(RadixEdge parentEdge, String query, int depth) {
		RadixNode node = parentEdge.node;
		if (depth == query.length()) {
			return; // End of query, node exists
		}
		char firstChar = query.charAt(depth);
		RadixEdge edge = node.findChild(firstChar);
		if (edge == null) {
			LeafNode newNode = new LeafNode();
			RadixEdge newEdge = new RadixEdge(query.substring(depth).intern(), newNode);
			RadixNode upgraded = node.addChildAndGetNode(firstChar, newEdge);
			if (upgraded != node) parentEdge.node = upgraded;
			return;
		}
		String edgeLabel = edge.label;
		int matchLen = 0, maxLen = Math.min(edgeLabel.length(), query.length() - depth);
		while (matchLen < maxLen && edgeLabel.charAt(matchLen) == query.charAt(depth + matchLen)) matchLen++;
		if (matchLen == edgeLabel.length()) {
			insertRadix(edge, query, depth + matchLen);
			return;
		}
		String commonPrefix = edgeLabel.substring(0, matchLen).intern();
		String oldSuffix = edgeLabel.substring(matchLen).intern();
		String newSuffix = (depth + matchLen < query.length()) ? query.substring(depth + matchLen).intern() : "";
		RadixNode splitNode = new LeafNode();
		edge.label = commonPrefix;
		RadixNode oldTarget = edge.node;
		edge.node = splitNode;
		splitNode = splitNode.addChildAndGetNode(oldSuffix.charAt(0), new RadixEdge(oldSuffix, oldTarget)); // oldSuffix already interned
		edge.node = splitNode;
		if (!newSuffix.isEmpty()) {
			LeafNode newNode = new LeafNode();
			RadixNode upgraded = splitNode.addChildAndGetNode(newSuffix.charAt(0), new RadixEdge(newSuffix, newNode));
			if (upgraded != splitNode) edge.node = upgraded;
		}
	}
	
	// Build sorted arrays - IDs are just the sorted index!
	private void buildSortedArrays(Map<String, Integer> freqMap) {
		int n = freqMap.size();
		
		// Extract entries directly into arrays (sorted by key)
		String[] keys = freqMap.keySet().toArray(new String[0]);
		Arrays.sort(keys);
		
		queryPool = new String[n];
		queryFrequencies = new short[n];
		for (int i = 0; i < n; i++) {
			queryPool[i] = keys[i].intern();
			queryFrequencies[i] = (short) Math.min(freqMap.get(keys[i]), Short.MAX_VALUE);
		}
		keys = null; // Help GC
	}
	
	// Binary search: find first index where query >= prefix
	private int findChunkStart(String prefix) {
		int lo = 0, hi = queryPool.length;
		while (lo < hi) {
			int mid = (lo + hi) / 2;
			if (queryPool[mid].compareTo(prefix) < 0) lo = mid + 1;
			else hi = mid;
		}
		return lo;
	}
	
	// Check if query starts with prefix
	private boolean startsWith(String query, String prefix) {
		if (query.length() < prefix.length()) return false;
		for (int i = 0; i < prefix.length(); i++)
			if (query.charAt(i) != prefix.charAt(i)) return false;
		return true;
	}
	
	// Find top 5 IDs for a prefix - NO OBJECT CREATION!
	private void findTop5IdsForPrefix(String prefix) {
		// Reset temp arrays
		for (int i = 0; i < 5; i++) {
			tempTop5Ids[i] = -1;
			tempScores[i] = Double.NEGATIVE_INFINITY;
			tempFreqs[i] = 0;
		}
		
		// Binary search to find start of chunk
		int start = findChunkStart(prefix);
		
		// Scan forward while queries match prefix
		for (int i = start; i < queryPool.length; i++) {
			if (!startsWith(queryPool[i], prefix)) break;
			
			String query = queryPool[i];
			int freq = queryFrequencies[i];
			double score = Math.pow(freq, FREQUENCY_EXPONENT) / query.length();
			insertIntoTop5((short)i, query, score, freq);
		}
	}
	
	// Insert into top 5 maintaining sorted order
	private void insertIntoTop5(short id, String query, double score, int freq) {
		int pos = -1;
		for (int i = 0; i < 5; i++) {
			if (tempTop5Ids[i] < 0) { pos = i; break; }
			if (score > tempScores[i]) { pos = i; break; }
			if (score == tempScores[i]) {
				if (freq > tempFreqs[i]) { pos = i; break; }
				if (freq == tempFreqs[i] && query.compareTo(queryPool[tempTop5Ids[i]]) < 0) { pos = i; break; }
			}
		}
		if (pos == -1) return;
		for (int i = 4; i > pos; i--) {
			tempTop5Ids[i] = tempTop5Ids[i-1];
			tempScores[i] = tempScores[i-1];
			tempFreqs[i] = tempFreqs[i-1];
		}
		tempTop5Ids[pos] = id;
		tempScores[pos] = score;
		tempFreqs[pos] = freq;
	}
	
	// Build cache using short[] IDs
	private void buildCache() {
		buildCacheRecursive(root, "");
	}
	
	private void buildCacheRecursive(RadixNode node, String pathSoFar) {
		if (node == null) return;
		
		// Find top 5 IDs for this prefix
		findTop5IdsForPrefix(pathSoFar);
		
		// Count valid entries
		int count = 0;
		for (int i = 0; i < 5; i++) if (tempTop5Ids[i] >= 0) count++;
		
		// Store as short[] instead of String[]!
		if (count > 0) {
			node.topSuggestionIds = new short[count];
			for (int i = 0; i < count; i++) {
				node.topSuggestionIds[i] = tempTop5Ids[i];
			}
		}
		
		// Recurse to children
		if (node instanceof ChainNode) {
			ChainNode cn = (ChainNode) node;
			buildCacheRecursive(cn.childEdge.node, pathSoFar + cn.childEdge.label);
		} else if (node instanceof BranchNode) {
			BranchNode bn = (BranchNode) node;
			for (RadixEdge edge : bn.childEdges) {
				buildCacheRecursive(edge.node, pathSoFar + edge.label);
			}
		}
	}
	
	public void processOldQueries(String oldQueryFile) {
		Map<String, Integer> freqMap = new HashMap<>();
		try (BufferedReader reader = new BufferedReader(new FileReader(oldQueryFile))) {
			String line;
			while ((line = reader.readLine()) != null) {
				line = line.replaceAll("\\s+", " ").trim();
				if (!line.isEmpty()) insertQuery(line, freqMap);
			}
			// Build sorted arrays (queryPool persists for lookups!)
			buildSortedArrays(freqMap);
			freqMap = null;
			// Build cache with short[] IDs
			buildCache();
			// DON'T free queryPool - we need it for guess()!
			// But we can free queryFrequencies
			queryFrequencies = null;
			// Force garbage collection to reduce peak memory
			System.gc();
		} catch (IOException e) {
			System.err.println("Error: " + e.getMessage());
		}
	}
	
	private short[] findTopIds(String prefix) {
		RadixNode current = root;
		int matched = 0;
		while (matched < prefix.length()) {
			RadixEdge edge = current.findChild(prefix.charAt(matched));
			if (edge == null) return null;
			String label = edge.label;
			int toCheck = Math.min(label.length(), prefix.length() - matched);
			for (int i = 0; i < toCheck; i++)
				if (label.charAt(i) != prefix.charAt(matched + i)) return null;
			matched += toCheck;
			current = edge.node;
		}
		return current.topSuggestionIds;
	}
	
	public String[] guess(char currChar, int currCharPosition) {
		currChar = Character.toLowerCase(currChar);
		currentPrefix = (currCharPosition == 0) ? "" + currChar : currentPrefix + currChar;
		
		short[] topIds = findTopIds(currentPrefix);
		
		// Convert short IDs to strings using queryPool
		for (int i = 0; i < 5; i++) {
			if (topIds != null && i < topIds.length && topIds[i] >= 0) {
				guesses[i] = queryPool[topIds[i]];  // O(1) lookup!
			} else {
				guesses[i] = null;
			}
		}
		return guesses;
	}
	
	public void feedback(boolean isCorrectGuess, String correctQuery) {
		if (correctQuery != null) currentPrefix = "";
	}
}

