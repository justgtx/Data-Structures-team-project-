/*
Authors (group members):
Email addresses of group members:
Group name:
Course:
Section:
Description of the overall algorithm:
RADIX TRIE (Compressed Trie) Implementation:
Instead of one node per character, we compress chains into edge labels.
Example: "algorithm" → Single edge with label "algorithm" instead of 9 nodes
Benefits: Less memory, potentially faster navigation
Maintains same accuracy and ranking logic as baseline
*/

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class QuerySidekick
{
	String[] guesses = new String[5];
	private RadixNode root;
	private String currentPrefix;
	
	private static final double FREQUENCY_EXPONENT = 1.0;
	
	// Radix Trie Node with edge labels
	private class RadixNode {
		Map<Character, RadixEdge> children; // First char → edge
		boolean isEndOfQuery;
		int frequency;
		String query;
		String[] topSuggestions;
		
		public RadixNode() {
			children = new HashMap<>();
			isEndOfQuery = false;
			frequency = 0;
			query = null;
			topSuggestions = null;
		}
	}
	
	// Edge with label (can be multiple characters)
	private class RadixEdge {
		String label; // e.g., "lgorithm" instead of individual nodes
		RadixNode node;
		
		public RadixEdge(String label, RadixNode node) {
			this.label = label;
			this.node = node;
		}
	}
	
	private class QuerySuggestion {
		String query;
		int frequency;
		double score;
		
		public QuerySuggestion(String query, int frequency) {
			this.query = query;
			this.frequency = frequency;
			this.score = Math.pow(frequency, FREQUENCY_EXPONENT) / query.length();
		}
	}
	
	public QuerySidekick()
	{
		root = new RadixNode();
		currentPrefix = "";
	}
	
	// Insert into Radix Trie
	private void insertQuery(String query) {
		if (query == null || query.isEmpty()) return;
		query = query.toLowerCase().intern();
		
		insertRadix(root, query, 0);
	}
	
	private void insertRadix(RadixNode node, String query, int depth) {
		if (depth == query.length()) {
			node.isEndOfQuery = true;
			node.frequency++;
			node.query = query;
			return;
		}
		
		char firstChar = query.charAt(depth);
		
		// No edge starting with this character - create new one
		if (!node.children.containsKey(firstChar)) {
			RadixNode newNode = new RadixNode();
			String remainingLabel = query.substring(depth);
			node.children.put(firstChar, new RadixEdge(remainingLabel, newNode));
			newNode.isEndOfQuery = true;
			newNode.frequency = 1;
			newNode.query = query;
			return;
		}
		
		// Edge exists - check for common prefix
		RadixEdge edge = node.children.get(firstChar);
		String edgeLabel = edge.label;
		int matchLen = 0;
		int maxLen = Math.min(edgeLabel.length(), query.length() - depth);
		
		while (matchLen < maxLen && edgeLabel.charAt(matchLen) == query.charAt(depth + matchLen)) {
			matchLen++;
		}
		
		// Full match - continue down
		if (matchLen == edgeLabel.length()) {
			insertRadix(edge.node, query, depth + matchLen);
			return;
		}
		
		// Partial match - need to split edge
		String commonPrefix = edgeLabel.substring(0, matchLen);
		String oldSuffix = edgeLabel.substring(matchLen);
		String newSuffix = (depth + matchLen < query.length()) ? 
		                   query.substring(depth + matchLen) : "";
		
		// Create intermediate node
		RadixNode splitNode = new RadixNode();
		
		// Old edge now points to split with remaining label
		edge.label = commonPrefix;
		RadixNode oldTarget = edge.node;
		edge.node = splitNode;
		
		// Add old suffix as child of split
		splitNode.children.put(oldSuffix.charAt(0), new RadixEdge(oldSuffix, oldTarget));
		
		// Add new suffix
		if (newSuffix.isEmpty()) {
			// Query ends at split point
			splitNode.isEndOfQuery = true;
			splitNode.frequency = 1;
			splitNode.query = query;
		} else {
			// Continue with new branch
			RadixNode newNode = new RadixNode();
			splitNode.children.put(newSuffix.charAt(0), new RadixEdge(newSuffix, newNode));
			newNode.isEndOfQuery = true;
			newNode.frequency = 1;
			newNode.query = query;
		}
	}
	
	private void buildCache() {
		buildCacheRecursive(root);
	}
	
	private void buildCacheRecursive(RadixNode node) {
		if (node == null) return;
		
		List<QuerySuggestion> allSuggestions = new ArrayList<>();
		collectAllQueries(node, allSuggestions);
		
		Collections.sort(allSuggestions, new Comparator<QuerySuggestion>() {
			@Override
			public int compare(QuerySuggestion a, QuerySuggestion b) {
				int scoreComp = Double.compare(b.score, a.score);
				if (scoreComp != 0) return scoreComp;
				if (a.frequency != b.frequency) return b.frequency - a.frequency;
				return a.query.compareTo(b.query);
			}
		});
		
		int count = Math.min(5, allSuggestions.size());
		if (count > 0) {
			node.topSuggestions = new String[count];
			for (int i = 0; i < count; i++) {
				node.topSuggestions[i] = allSuggestions.get(i).query;
			}
		}
		
		for (RadixEdge edge : node.children.values()) {
			buildCacheRecursive(edge.node);
		}
	}
	
	private void collectAllQueries(RadixNode node, List<QuerySuggestion> suggestions) {
		if (node == null) return;
		
		if (node.isEndOfQuery) {
			suggestions.add(new QuerySuggestion(node.query, node.frequency));
		}
		
		for (RadixEdge edge : node.children.values()) {
			collectAllQueries(edge.node, suggestions);
		}
	}
	
	public void processOldQueries(String oldQueryFile)
	{
		try (BufferedReader reader = new BufferedReader(new FileReader(oldQueryFile))) {
			String line;
			while ((line = reader.readLine()) != null) {
				line = line.replaceAll("\\s+", " ").trim();
				if (!line.isEmpty()) {
					insertQuery(line);
				}
			}
			buildCache();
		} catch (IOException e) {
			System.err.println("Error reading old queries file: " + e.getMessage());
		}
	}
	
	// Navigate Radix Trie with prefix matching
	private String[] findTopQueries(String prefix) {
		RadixNode current = root;
		int matched = 0;
		
		while (matched < prefix.length()) {
			char nextChar = prefix.charAt(matched);
			
			if (!current.children.containsKey(nextChar)) {
				return new String[0];
			}
			
			RadixEdge edge = current.children.get(nextChar);
			String label = edge.label;
			
			// Check how much of the label matches remaining prefix
			int remainingPrefix = prefix.length() - matched;
			int toCheck = Math.min(label.length(), remainingPrefix);
			
			for (int i = 0; i < toCheck; i++) {
				if (label.charAt(i) != prefix.charAt(matched + i)) {
					// Prefix doesn't match this edge
					return new String[0];
				}
			}
			
			matched += toCheck;
			
			if (matched < prefix.length()) {
				// Need to continue down
				current = edge.node;
			} else if (toCheck < label.length()) {
				// Prefix ends in middle of edge - use edge's target node
				current = edge.node;
			} else {
				// Matched entire edge label
				current = edge.node;
			}
		}
		
		if (current.topSuggestions != null) {
			return current.topSuggestions;
		}
		return new String[0];
	}
	
	public String[] guess(char currChar, int currCharPosition)
	{
		currChar = Character.toLowerCase(currChar);
		
		if (currCharPosition == 0) {
			currentPrefix = "" + currChar;
		} else {
			currentPrefix = currentPrefix + currChar;
		}
		
		String[] topQueries = findTopQueries(currentPrefix);
		
		for (int i = 0; i < 5; i++) {
			if (i < topQueries.length) {
				guesses[i] = topQueries[i];
			} else {
				guesses[i] = null;
			}
		}
		
		return guesses;
	}
	
	public void feedback(boolean isCorrectGuess, String correctQuery)
	{
		if (correctQuery != null) {
			currentPrefix = "";
		}
	}
}

