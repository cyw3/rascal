package org.rascalmpl.parser.gtd.result.out;

import java.net.URI;

import org.rascalmpl.parser.gtd.location.PositionStore;
import org.rascalmpl.parser.gtd.util.ArrayList;

public interface INodeConstructorFactory<T, P> {
	
	T createCharNode(int charNumber);
	
	T createLiteralNode(int[] characters, Object production);
	
	T createSortNode(ArrayList<T> children, Object production);
	
	T createSubListNode(ArrayList<T> children, Object production);
	
	T createListNode(ArrayList<T> children, Object production);
	
	T createAmbiguityNode(ArrayList<T> alternatives);
	
	T createCycleNode(int depth, Object production);
	
	T createSubListCycleNode(Object production);
	
	T createSubListAmbiguityNode(ArrayList<T> alternatives);
	
	T createListAmbiguityNode(ArrayList<T> alternatives);
	
	T createRecoveryNode(int[] characters);
	
	ArrayList<T> getChildren(T node);
	
	P createPositionInformation(URI input, int offset, int endOffset, PositionStore positionStore);
	
	T addPositionInformation(T node, P location);
	
	Object getRhs(Object production);
	
	boolean isAmbiguityNode(T node);
	
	Object getProductionFromNode(T node);
}
