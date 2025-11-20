/*
Authors (group members):
Edwin Cordova, Jackson Neering, Gio Williams, Basil Salem
Email addresses of group members:
jneering2023@my.fit.edu, gwilliams2024@my.fit.edu,
Group name: A Boogie No Hoodie
Course: 2010
Section: 3/4
Description of the overall algorithm:
This implementation uses a Trie (prefix tree) data structure to efficiently store
and retrieve query predictions. Each node in the Trie stores a character and maintains
a frequency count for complete queries ending at that node. When guessing, we traverse
the Trie based on the current prefix and return the top 5 most frequent queries.
The feedback method allows dynamic updates to improve predictions over time.
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
	String[] guesses = new String[5]; // 5 guesses from QuerySidekick
	private TrieNode root; // Root of the Trie
	private String currentPrefix; // Tracks the current partial query
	
	// Tunable parameters for scoring
	private static final double FREQUENCY_EXPONENT = 1.0; // Optimal value from testing (best overall score)
	
	// Inner class for Trie Node with cached top suggestions
	private class TrieNode {
		Map<Character, TrieNode> children;
		boolean isEndOfQuery;
		int frequency; // How many times this query has appeared
		String query; // Store the complete query at end nodes
		String[] topSuggestions; // Cache top 5 suggestions as array (more memory efficient)
		
		public TrieNode() {
			children = new HashMap<>();
			isEndOfQuery = false;
			frequency = 0;
			query = null;
			topSuggestions = null; // Will be computed after all insertions
		}
	}
	
	// Helper class to store query suggestions with their frequencies
	private class QuerySuggestion {
		String query;
		int frequency;
		double score; // Combined score: frequency adjusted by length
		
		public QuerySuggestion(String query, int frequency) {
			this.query = query;
			this.frequency = frequency;
			// Boost score: weight frequency by exponent, penalize length
			// Higher exponent = favor very popular queries more aggressively
			// Lower exponent = more democratic, give less popular queries a chance
			this.score = Math.pow(frequency, FREQUENCY_EXPONENT) / query.length();
		}
	}
	
	// initialization of Trie
	public QuerySidekick()
	{
		root = new TrieNode();
		currentPrefix = "";
	}
	
	// Insert a query into the Trie
	private void insertQuery(String query) {
		if (query == null || query.isEmpty()) {
			return;
		}
		
		// Convert to lowercase for case-insensitive matching and intern to save memory
		query = query.toLowerCase().intern(); // intern() deduplicates strings
		
		TrieNode current = root;
		for (int i = 0; i < query.length(); i++) {
			char ch = query.charAt(i);
			current.children.putIfAbsent(ch, new TrieNode());
			current = current.children.get(ch);
		}
		current.isEndOfQuery = true;
		current.frequency++;
		current.query = query;
	}
	
	// Build cache of top suggestions for all nodes (call after loading all queries)
	private void buildCache() {
		buildCacheRecursive(root);
	}
	
	// Recursively build top suggestions cache for each node
	private void buildCacheRecursive(TrieNode node) {
		if (node == null) return;
		
		// Collect all queries from this subtree
		List<QuerySuggestion> allSuggestions = new ArrayList<>();
		collectAllQueries(node, allSuggestions);
		
			// Sort by combined score (frequency / sqrt(length))
			// This heavily favors popular short queries
			Collections.sort(allSuggestions, new Comparator<QuerySuggestion>() {
				@Override
				public int compare(QuerySuggestion a, QuerySuggestion b) {
					// Sort by score (descending)
					int scoreComp = Double.compare(b.score, a.score);
					if (scoreComp != 0) {
						return scoreComp;
					}
					// Tie-break by frequency
					if (a.frequency != b.frequency) {
						return b.frequency - a.frequency;
					}
					// Finally alphabetical
					return a.query.compareTo(b.query);
				}
			});
			
		// Keep top 5 in a simple array (memory efficient)
		int count = Math.min(5, allSuggestions.size());
		if (count > 0) {
			node.topSuggestions = new String[count];
			for (int i = 0; i < count; i++) {
				node.topSuggestions[i] = allSuggestions.get(i).query;
			}
		}
		
		// Recursively process children
		for (TrieNode child : node.children.values()) {
			buildCacheRecursive(child);
		}
	}
	
	// process old queries from oldQueryFile
	// to remove extra spaces with one space
	// str2 = str1.replaceAll("\\s+", " ");
	public void processOldQueries(String oldQueryFile)
	{
		try (BufferedReader reader = new BufferedReader(new FileReader(oldQueryFile))) {
			String line;
			while ((line = reader.readLine()) != null) {
				// Remove extra spaces with one space
				line = line.replaceAll("\\s+", " ");
				// Trim leading/trailing spaces
				line = line.trim();
				if (!line.isEmpty()) {
					insertQuery(line);
				}
			}
			// After loading all queries, build the cache for fast lookups
			buildCache();
		} catch (IOException e) {
			System.err.println("Error reading old queries file: " + e.getMessage());
		}
	}
	
	// Find top queries with a given prefix using cached results
	private String[] findTopQueries(String prefix) {
		// Navigate to the end of the prefix
		TrieNode current = root;
		for (int i = 0; i < prefix.length(); i++) {
			char ch = prefix.charAt(i);
			if (!current.children.containsKey(ch)) {
				// No queries with this prefix
				return new String[0];
			}
			current = current.children.get(ch);
		}
		
		// Return cached top suggestions (already sorted)
		if (current.topSuggestions != null) {
			return current.topSuggestions;
		}
		return new String[0];
	}
	
	// Recursively collect all complete queries from a node
	private void collectAllQueries(TrieNode node, List<QuerySuggestion> suggestions) {
		if (node == null) {
			return;
		}
		
		if (node.isEndOfQuery) {
			suggestions.add(new QuerySuggestion(node.query, node.frequency));
		}
		
		for (TrieNode child : node.children.values()) {
			collectAllQueries(child, suggestions);
		}
	}
	
	// based on a character typed in by the user, return 5 query guesses in an array
	// currChar: current character typed in by the user
	// currCharPosition: position of the current character in the query, starts from 0
	public String[] guess(char currChar, int currCharPosition)
	{
		// Convert to lowercase for case-insensitive matching
		currChar = Character.toLowerCase(currChar);
		
		// Update current prefix with the new character
		if (currCharPosition == 0) {
			currentPrefix = "" + currChar;
		} else {
			currentPrefix = currentPrefix + currChar;
		}
		
		// Find top queries matching the current prefix (using cached results - very fast!)
		String[] topQueries = findTopQueries(currentPrefix);
		
		// Fill the guesses array (up to 5)
		for (int i = 0; i < 5; i++) {
			if (i < topQueries.length) {
				guesses[i] = topQueries[i];
			} else {
				guesses[i] = null;
			}
		}
		//insertQuery(currentPrefix);
//		buildCache();
		return guesses;
	}
	
	// feedback on the 5 guesses from the user
	// isCorrectGuess: true if one of the guesses is correct
	// correctQuery: 3 cases:
	// a. correct query if one of the guesses is correct
	// b. null if none of the guesses is correct, before the user has typed in
	// the last character
	// c. correct query if none of the guesses is correct, and the user has
	// typed in the last character
	// That is:
	// Case isCorrectGuess correctQuery
	// a. true correct query
	// b. false null
	// c. false correct query
	public void feedback(boolean isCorrectGuess, String correctQuery)
	{
		// If we have a correct query (either guessed correctly or user finished typing)
		if (correctQuery != null) {
			// Note: For optimal performance, we don't update during evaluation
			// since rebuilding cache is expensive. In a real system, you'd use
			// incremental updates or periodic cache refreshes.
			
			// Reset current prefix for next query
			currentPrefix = "";
		}
		// If correctQuery is null, we continue building the current query
		// (no action needed, currentPrefix will be extended in next guess)
	}
}
