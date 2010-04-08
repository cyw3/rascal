package org.rascalmpl.library.viz.Figure;

import java.util.HashMap;
import java.util.HashSet;

import org.eclipse.imp.pdb.facts.IConstructor;
import org.eclipse.imp.pdb.facts.IList;
import org.eclipse.imp.pdb.facts.IString;
import org.eclipse.imp.pdb.facts.IValue;
import org.eclipse.imp.pdb.facts.IValueFactory;
import org.rascalmpl.interpreter.IEvaluatorContext;
import org.rascalmpl.interpreter.utils.RuntimeExceptionFactory;
import org.rascalmpl.values.ValueFactoryFactory;

/**
 * Tree layout. Given a tree consisting of a list of nodes and edges, place them in a space conserving layout.
 * We use the algorithm described in:
 * E. M. Reingold, J. S. Tilford, Tidier Drawings of Trees, 
 * IEEE Transactions on Software Engineering, Volume 7 ,  Issue 2  (March 1981), Pages: 223-228  
 * 
 * @author paulk
 *
 */
public class Tree extends Figure {
	protected HashMap<String,TreeNode> nodeMap;
	private HashSet<TreeNode> hasParent;
	private TreeNodeRaster raster;
	TreeNode root = null;
	
	Tree(FigurePApplet vlp, PropertyManager inheritedProps, IList props, IList nodes, IList edges, IString rootName, IEvaluatorContext ctx) {
		super(vlp, inheritedProps, props, ctx);		
		nodeMap = new HashMap<String,TreeNode>();
		hasParent = new HashSet<TreeNode>();
		raster = new TreeNodeRaster();
		for(IValue v : nodes){
			IConstructor c = (IConstructor) v;
			Figure ve = FigureFactory.make(vlp, c, properties, ctx);
			String name = ve.getIdProperty();
			if(name.length() == 0)
				throw RuntimeExceptionFactory.illegalArgument(v, ctx.getCurrentAST(), ctx.getStackTrace());
			TreeNode tn = new TreeNode(vlp, inheritedProps, props, ve, ctx);
			nodeMap.put(name, tn);
		}
		
		IValueFactory vf = ValueFactoryFactory.getValueFactory();
		IList emptyList = vf.list();

		for(IValue v : edges){
			IConstructor c = (IConstructor) v;
			int iFrom;
			int iTo;
			IList edgeProperties;
			if(c.arity() == 3){
				edgeProperties = (IList) c.get(0);
				iFrom = 1;
				iTo = 2;
			} else {
				edgeProperties = emptyList;
				iFrom = 0;
				iTo = 1;
			}
			String from = ((IString)c.get(iFrom)).getValue();

			TreeNode fromNode = nodeMap.get(from);
			if(fromNode == null)
				throw RuntimeExceptionFactory.illegalArgument(v, ctx.getCurrentAST(), ctx.getStackTrace());
			String to = ((IString)c.get(iTo)).getValue();
			TreeNode toNode = nodeMap.get(to);
			if(toNode == null)
				throw RuntimeExceptionFactory.illegalArgument(v, ctx.getCurrentAST(), ctx.getStackTrace());
			if(hasParent.contains(toNode))
				System.err.println("NOT A TREE");
			hasParent.add(toNode);
			fromNode.addChild(properties, edgeProperties, toNode, ctx);
		}
		
		root = nodeMap.get(rootName.getValue());
		
		if(root == null)
			throw RuntimeExceptionFactory.illegalArgument(rootName, ctx.getCurrentAST(), ctx.getStackTrace());
	}
	
	@Override
	void bbox() {
		raster.clear();
		root.shapeTree(left, top, raster);
		width = root.width;
		height = root.height;
	}
	
	@Override
	void bbox(float left, float top) {
		this.left = left;
		this.top = top;
		raster.clear();
		root.shapeTree(left, top, raster);
		width = root.width;
		height = root.height;
	}
	
	@Override
	void draw(float left, float top) {
		this.left = left;
		this.top = top;
		left += leftDragged;
		top += topDragged;
		System.err.printf("Tree.draw(%f,%f)\n", left, top);
		vlp.pushMatrix();
		vlp.translate(left, top);
		applyProperties();
		root.draw();
		vlp.popMatrix();
	}
	
	@Override
	public boolean mouseInside(int mousex, int mousey){
		return root.mouseInside(mousex, mousey) || 
		       super.mouseInside(mousex, mousey);
	}
	
	@Override
	public boolean mouseOver(int mousex, int mousey){
		return root.mouseOver(mousex, mousey) ||
		       super.mouseOver(mousex, mousey);
	}
	
	@Override
	public boolean mousePressed(int mousex, int mousey){
		if(root.mousePressed(mousex, mousey)){
			bbox();
			return true;
		}
		return false;
	}
	
	@Override
	public boolean mouseDragged(int mousex, int mousey){
		return root.mouseDragged(mousex, mousey) ||
		       super.mouseDragged(mousex, mousey);
	}
}
