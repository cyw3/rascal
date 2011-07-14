/*******************************************************************************
 * Copyright (c) 2009-2011 CWI
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:

 *   * Jurgen J. Vinju - Jurgen.Vinju@cwi.nl - CWI
 *   * Tijs van der Storm - Tijs.van.der.Storm@cwi.nl
 *   * Paul Klint - Paul.Klint@cwi.nl - CWI
 *   * Mark Hills - Mark.Hills@cwi.nl (CWI)
 *   * Arnold Lankamp - Arnold.Lankamp@cwi.nl
*******************************************************************************/
package org.rascalmpl.parser;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.eclipse.imp.pdb.facts.IConstructor;
import org.eclipse.imp.pdb.facts.IList;
import org.eclipse.imp.pdb.facts.IListWriter;
import org.eclipse.imp.pdb.facts.ISet;
import org.eclipse.imp.pdb.facts.ISourceLocation;
import org.eclipse.imp.pdb.facts.IValue;
import org.eclipse.imp.pdb.facts.IValueFactory;
import org.eclipse.imp.pdb.facts.exceptions.FactTypeUseException;
import org.rascalmpl.ast.ASTStatistics;
import org.rascalmpl.ast.AbstractAST;
import org.rascalmpl.ast.Command;
import org.rascalmpl.ast.Expression;
import org.rascalmpl.ast.Header;
import org.rascalmpl.ast.Module;
import org.rascalmpl.ast.Statement;
import org.rascalmpl.ast.Toplevel;
import org.rascalmpl.interpreter.asserts.Ambiguous;
import org.rascalmpl.interpreter.asserts.ImplementationError;
import org.rascalmpl.interpreter.staticErrors.SyntaxError;
import org.rascalmpl.interpreter.utils.Symbols;
import org.rascalmpl.parser.gtd.util.PointerKeyedHashMap;
import org.rascalmpl.semantics.dynamic.Tree;
import org.rascalmpl.values.ValueFactoryFactory;
import org.rascalmpl.values.uptr.Factory;
import org.rascalmpl.values.uptr.ProductionAdapter;
import org.rascalmpl.values.uptr.SymbolAdapter;
import org.rascalmpl.values.uptr.TreeAdapter;

/**
 * Uses reflection to construct an AST hierarchy from a 
 * UPTR parse node of a rascal program.
 */
public class ASTBuilder {
	private static final String RASCAL_SORT_PREFIX = "_";
	private static final String MODULE_SORT = "Module";
	private static final String PRE_MODULE_SORT = "PreModule";

	// this tree should never appear in "nature", so we can use it as a dummy
    private static Expression dummyEmptyTree;
    
    private PointerKeyedHashMap<IConstructor, AbstractAST> ambCache = new PointerKeyedHashMap<IConstructor, AbstractAST>();
    private PointerKeyedHashMap<IConstructor, AbstractAST> sortCache = new PointerKeyedHashMap<IConstructor, AbstractAST>();
    private PointerKeyedHashMap<IConstructor, AbstractAST> lexCache = new PointerKeyedHashMap<IConstructor, AbstractAST>();
    
    private PointerKeyedHashMap<IValue, Expression> constructorCache = new PointerKeyedHashMap<IValue, Expression>();
    private ISourceLocation lastSuccess = null;
    
    private final static HashMap<String, Class<?>> astClasses = new HashMap<String,Class<?>>();
	private final static ClassLoader classLoader = ASTBuilder.class.getClassLoader();
    
	public ASTBuilder(){
		super();
		
		if(dummyEmptyTree == null){
			IValueFactory vf = ValueFactoryFactory.getValueFactory();
			dummyEmptyTree = makeAmb("Expression", (IConstructor) Factory.Tree_Amb.make(vf, vf.list()), Collections.<Expression>emptyList());
		}
	}
	
	public static <T extends AbstractAST> T make(String sort, IConstructor src, Object... args) {
		return make(sort, "Default", src, args);
	}
	
	public static  <T extends Expression> T makeExp(String cons, IConstructor src, Object... args) {
		return make("Expression", cons, src, args);
	}
	
	public static <T extends Statement> T makeStat(String cons, IConstructor src, Object... args) {
		return make("Statement", cons, src, args);
	}
	
	public static <T extends AbstractAST> T makeAmb(String sort, IConstructor src, Object... args) {
		return make(sort, "Ambiguity", src, args);
	}
	
	public static <T extends AbstractAST> T makeLex(String sort, IConstructor src, Object... args) {
		return make(sort, "Lexical", src, args);
	}
	
	@SuppressWarnings("unchecked")
	public static <T extends AbstractAST> T make(String sort, String cons, IConstructor src, Object... args) {
		Class<?>[] formals = new Class<?>[args.length];
		for (int i = 0; i < args.length; i++) {
			Class<?> clazz = args[i].getClass();
			
			if (args[i] instanceof java.util.List) {
				formals[i] = java.util.List.class;
			}
			else if (args[i] instanceof java.lang.String) {
				formals[i] = java.lang.String.class;
			}
			else {
				String pkg = clazz.getPackage().getName();
				if (pkg.contains(".ast")) {
					formals[i] = clazz.getSuperclass();
				} else if (pkg.contains(".dynamic")) {
					formals[i] = clazz.getSuperclass().getSuperclass();
				}
				else {
					formals[i] = clazz.getSuperclass().getSuperclass().getSuperclass();
				}
			}
		}
		
		return (T) callMakerMethod(sort, cons, formals, src, args);
	}

	public ISourceLocation getLastSuccessLocation() {
		return lastSuccess;
	}
	
	public Module buildModule(IConstructor parseTree) throws FactTypeUseException {
		IConstructor tree =  parseTree;
		
		if (TreeAdapter.isAppl(tree)) {
			if (sortName(tree).equals(MODULE_SORT)) {
				// t must be an appl so call buildValue directly
				return (Module) buildValue(tree);
			}
			else if (sortName(tree).equals(PRE_MODULE_SORT)) {
				// TODO temporary solution while bootstrapping (if we regenerate the ast hierarchy this can be solved more elegantly)
				IList moduleArgs = (IList) tree.get(1);
				IConstructor headerTree = (IConstructor) moduleArgs.get(0);
				Header header = (Header) buildValue(headerTree);
				return make("Module", tree, header, make("Body","Toplevels", (IConstructor) moduleArgs.get(2), Collections.<Toplevel>emptyList())); 
			}
			return buildSort(parseTree, MODULE_SORT);
		}
		if (TreeAdapter.isAmb(tree)) {
			ISet alts = TreeAdapter.getAlternatives(tree);
			for (IValue val: alts) {
				IConstructor t = (IConstructor) TreeAdapter.getArgs((IConstructor)val).get(1);
				// This *prefers* the first Rascal Module it encounters in the set of alts.
				// So if the Rascal syntax for modules itself would be ambiguous
				// you get just one of them (unknown which).
				if (sortName((IConstructor) val).equals(MODULE_SORT)) {
					// t must be an appl so call buildValue directly
					return (Module) buildValue(t);
				}
				else if (sortName((IConstructor) val).equals(PRE_MODULE_SORT)) {
					throw new Ambiguous(parseTree);
				}
			}
		}
		throw new ImplementationError("Parse of module returned invalid tree.");
	}
	
	public Expression buildExpression(IConstructor parseTree) {
		return buildSort(parseTree, "Expression");
	}
	
	public Statement buildStatement(IConstructor parseTree) {
		return buildSort(parseTree, "Statement");
	}
	
	public Command buildCommand(IConstructor parseTree) {
		return buildSort(parseTree, "Command");
	}
	
	@SuppressWarnings("unchecked")
	private <T extends AbstractAST> T buildSort(IConstructor parseTree, String sort) {
		if (TreeAdapter.isAppl(parseTree)) {
			IConstructor tree = (IConstructor) TreeAdapter.getArgs(parseTree).get(1);
			
			if (TreeAdapter.isAmb(tree)) {
				throw new Ambiguous(tree);
			}
			
			if (sortName(tree).equals(sort)) {
				return (T) buildValue(tree);
			}
		} else if (TreeAdapter.isAmb(parseTree)) {
			for (IValue alt : TreeAdapter.getAlternatives(parseTree)) {
				IConstructor tree = (IConstructor) alt;

				if (sortName(tree).equals(sort)) {
					AbstractAST value = buildValue(tree);
					if (value != null) {
						return (T) value;
					}
				}
			}
			throw new SyntaxError(sort, TreeAdapter.getLocation(parseTree)); // TODO Always @ offset = 0?
		}
		
		throw new ImplementationError("This is not a " + sort +  ": " + parseTree);
	}
	
	public AbstractAST buildValue(IValue arg)  {
		IConstructor tree = (IConstructor) arg;
		
		if (TreeAdapter.isList(tree)) {
			throw new ImplementationError("buildValue should not be called on a list");
		}
		
		if (TreeAdapter.isAmb(tree)) {
			return filter(tree);
		}
		
		if (!TreeAdapter.isAppl(tree)) {
			throw new UnsupportedOperationException();
		}	
		
		if (isLexical(tree)) {
			if (TreeAdapter.isRascalLexical(tree)) {
				return buildLexicalNode(tree);
			}
			return buildLexicalNode((IConstructor) ((IList) ((IConstructor) arg).get("args")).get(0));
		}
		
		if (sortName(tree).equals("Pattern") && isEmbedding(tree)) {
			return lift(tree, true);
		}

		if (sortName(tree).equals("Expression")) {
			if (isEmbedding(tree)) {
				return lift(tree, false);
			}
		}
		
		return buildContextFreeNode((IConstructor) arg);
	}

	private List<AbstractAST> buildList(IConstructor in)  {
		IList args = TreeAdapter.getListASTArgs(in);
		List<AbstractAST> result = new ArrayList<AbstractAST>(args.length());
		for (IValue arg: args) {
			IConstructor tree = (IConstructor) arg;

			if (TreeAdapter.isAmbiguousList(tree)) {
				// unflattened list due to nested ambiguity
				List<AbstractAST> elems = filterList(tree);
				
				if (elems != null) {
					result.addAll(elems);
				}
				else {
					return null;
				}
			}
			else {
				AbstractAST elem = buildValue(arg);

				if (elem == null) {
					return null; // filtered
				}
				result.add(elem);
			}
		}
		return result;
	}

	private AbstractAST buildContextFreeNode(IConstructor tree)  {
		AbstractAST cached = sortCache.get(tree);
		
		if (cached != null) {
			return cached;
		}
		
		String constructorName = TreeAdapter.getConstructorName(tree);
		if (constructorName == null) {
			throw new ImplementationError("All Rascal productions should have a constructor name: " + TreeAdapter.getProduction(tree));
		}
		
		String cons = capitalize(constructorName);
		String sort = sortName(tree);
		
		if (sort.length() == 0) {
			throw new ImplementationError("Could not retrieve sort name for " + tree);
		}
		sort = sort.equalsIgnoreCase("pattern") ? "Expression" : capitalize(sort); 
		
		if (sort.equals("Mapping")) {
			sort = "Mapping_Expression";
		}

		IList args = getASTArgs(tree);
		int arity = args.length();
		Class<?> formals[] = new Class<?>[arity];
		Object actuals[] = new Object[arity];

		ASTStatistics total = new ASTStatistics();

		int i = 0;
		for (IValue arg : args) {
			IConstructor argTree = (IConstructor) arg;

			if (TreeAdapter.isList(argTree)) {
				actuals[i] = buildList((IConstructor) arg);
				formals[i] = List.class;

				if (actuals[i] == null) { // filtered
					return null;
				}

				for (Object ast : ((java.util.List<?>) actuals[i])) {
					total.add(((AbstractAST) ast).getStats());
				}
			}
			else if (TreeAdapter.isAmbiguousList(argTree)) {
				actuals[i] = filterList(argTree);
				formals[i] = List.class;

				if (actuals[i] == null) { // filtered
					return null;
				}

				for (Object ast : ((java.util.List<?>) actuals[i])) {
					ASTStatistics stats = ((AbstractAST) ast).getStats();
					total.add(stats);
				}
			}
			else {
				actuals[i] = buildValue(arg);
				if (actuals[i] == null) { // filtered
					return null;
				}
				// TODO: find a better way to ensure we get the right class back
                if (actuals[i].getClass().getPackage().getName().contains(".ast")) {
                    formals[i] = actuals[i].getClass().getSuperclass();
                } 
                else if (actuals[i].getClass().getName().contains("dynamic.Tree")) {
                	formals[i] = org.rascalmpl.ast.Expression.class;
                }
                else {
                	formals[i] = actuals[i].getClass().getSuperclass().getSuperclass();
                }


				ASTStatistics stats = ((AbstractAST) actuals[i]).getStats();
				total.add(stats);
			}
			i++;
		}

		// TODO throw away code!
//		if (sort.equals("Type") && cons.equals("Symbol")) {
//			if (actuals.length == 2 && (actuals[1] instanceof Sym.Nonterminal || actuals[1] instanceof Sym.Labeled || actuals[1] instanceof Sym.Parameter)) {
//				return null; // filtered
//			}
//		}
		
		AbstractAST ast = callMakerMethod(sort, cons, formals, tree, actuals);
		
		// TODO: This is a horrible hack. The pattern Statement s : `whatever` should
		// be a concrete syntax pattern, but is not recognized as such because of the
		// Statement s in front (the "concrete-ness" is nested inside). This propagates
		// the pattern type up to this level. It would be good to find a more principled
		// way to do this.
		if (ast instanceof org.rascalmpl.ast.Expression.TypedVariableBecomes || ast instanceof org.rascalmpl.ast.Expression.VariableBecomes) {
			org.rascalmpl.ast.Expression astExp = (org.rascalmpl.ast.Expression)ast;
			if (astExp.hasPattern() && astExp.getPattern()._getType() != null) {
				astExp._setType(astExp.getPattern()._getType());
			}
		}
		
		ast.setStats(total);
		sortCache.putUnsafe(tree, ast);
		lastSuccess = ast.getLocation();
		return ast;
	}
	
	private AbstractAST buildLexicalNode(IConstructor tree) {
		AbstractAST cached = lexCache.get(tree);
		if (cached != null) {
			return cached;
		}
		String sort = capitalize(sortName(tree));

		if (sort.length() == 0) {
			throw new ImplementationError("could not retrieve sort name for " + tree);
		}
		Class<?> formals[] = new Class<?>[] {String.class };
		Object actuals[] = new Object[] { new String(TreeAdapter.yield(tree)) };

		AbstractAST result = callMakerMethod(sort, "Lexical", formals, tree, actuals);
		lexCache.putUnsafe(tree, result);
		return result;
	}
	
	private AbstractAST filter(IConstructor tree) {
		AbstractAST cached = ambCache.get(tree);
		if (cached != null) {
			return cached;
		}
		ISet altsIn = TreeAdapter.getAlternatives(tree);
		java.util.List<AbstractAST> altsOut = new ArrayList<AbstractAST>(altsIn.size());
		String sort = "";
		ASTStatistics ref = null;
		Ambiguous lastCaughtACP = null;
		
		for (IValue alt : altsIn) {
			sort = sortName((IConstructor) alt);
			AbstractAST ast = null;
			try {
				ast = buildValue(alt);
			} catch (Ambiguous acp) {
				lastCaughtACP = acp;
			}
			
			if (ast == null) {
				continue;
			}
			
			if (ref == null) {
				ref = ast.getStats();
				altsOut.add(ast);
			}
			else {
				ref = filter(altsOut, ast, ref);
			}
		}
		
		if (altsOut.size() == 0) {
			if (null != lastCaughtACP) {
				throw lastCaughtACP;
			}
			return null; // this could happen in case of nested ambiguity
//			throw new SyntaxError("concrete syntax pattern", tree.getLocation());
		}
		
		if (altsOut.size() == 1) {
			return altsOut.iterator().next();
		}

		// Concrete syntax is lifted to Expression
		sort = sort.equalsIgnoreCase("pattern") ? "Expression" : capitalize(sort); 

		Class<?> formals[] = new Class<?>[]  { List.class };
		Object actuals[] = new Object[] {  altsOut };

		AbstractAST ast = callMakerMethod(sort, "Ambiguity", formals, tree, actuals);
		
		ast.setStats(ref != null ? ref : new ASTStatistics());
		
		ambCache.putUnsafe(tree, ast);
		return ast;
	}

	private <T extends AbstractAST> ASTStatistics filter(java.util.List<T> altsOut, T ast, ASTStatistics ref) {
		ASTStatistics stats = ast.getStats();
		return filter(altsOut, ast, ref, stats);
	}

	private <T> ASTStatistics filter(java.util.List<T> altsOut, T ast, ASTStatistics ref, ASTStatistics stats) {
		switch(ref.compareTo(stats)) {
		case 1:
			ref = stats;
			altsOut.clear();
			altsOut.add(ast);
			break;
		case 0:
			altsOut.add(ast);
			break;
		case -1:
			// do nothing
		}
		return ref;
	}

	private List<AbstractAST> filterList(IConstructor argTree) {
		ISet alts = TreeAdapter.getAlternatives(argTree);
		ASTStatistics ref = new ASTStatistics();
		List<List<AbstractAST>> result = new ArrayList<List<AbstractAST>>(/* size unknown */);
	
		for (IValue alt : alts) {
			List<AbstractAST> list = buildList((IConstructor) alt);
			
			if (list == null) {
				continue;
			}
			
			ASTStatistics listStats = new ASTStatistics();
			
			for (AbstractAST ast : list) {
				ASTStatistics stats = ast.getStats();
				listStats.add(stats);
			}
			
			if (ref == null) {
				ref = listStats;
				result.add(list);
			}
			else {
				ref = filter(result, list, ref, listStats);
			}
		}
		
		switch(result.size()) {
		case 1:
			return result.get(0);
		case 0: 
			return null;
//			throw new ImplementationError("Accidentally all ambiguous derivations of a list have been filtered", argTree.getLocation());
		default:
			throw new Ambiguous(argTree);
		}
	}

	/**
	 * Removes patterns like <PROGRAM p> where the <...> hole is not nested in a place
	 * where a PROGRAM is expected. Also, patterns that directly nest concrete syntax patterns
	 * again, like `<`...`>` are filtered.
	 */
	private Expression filterNestedPattern(IConstructor antiQuote, IConstructor pattern) {
		ISet alternatives = TreeAdapter.getAlternatives(pattern);
		List<Expression> result = new ArrayList<Expression>(alternatives.size());
		 
		IConstructor expected = ProductionAdapter.getType(TreeAdapter.getProduction(antiQuote));

		// any alternative that is a typed variable must be parsed using a 
		// MetaVariable that produced exactly the same type as is declared inside
		// the < > brackets.
		for (IValue alt : alternatives) {
			if (isEmbedding((IConstructor) alt)) {
				continue; // filter direct nesting
			}
			
			Expression exp = (Expression) buildValue(alt);
		
			if (exp != null && correctlyNestedPattern(expected, exp)) {
				result.add(exp);
			}
		}
		
		if (result.size() == 1) {
			return result.get(0);
		}
		
		if (result.size() == 0) {
			return null;
		}
		
		return makeAmb("Expression", antiQuote, result);
	}

	private AbstractAST lift(IConstructor tree, boolean match) {
		AbstractAST cached = constructorCache.get(tree);
		if (cached != null) {
			if (cached == dummyEmptyTree) {
				return null;
			}
			return cached;
		}
		
		if (TreeAdapter.isEpsilon(tree)) {
			constructorCache.putUnsafe(tree, dummyEmptyTree);
			return null;
		}
		
		IConstructor pattern = getConcretePattern(tree);
		Expression ast = liftRec(pattern);
		
		if (ast != null) {
			ASTStatistics stats = ast.getStats();
			stats.setConcreteFragmentCount(1);
			stats.setConcreteFragmentSize(TreeAdapter.getLocation(pattern).getLength());
			
			if (stats.isAmbiguous()) {
				throw new Ambiguous(ast.getTree());
			}
		}
		
		constructorCache.putUnsafe(tree, ast);
		return ast;
	}

	private Expression stats(IConstructor in, Expression out, ASTStatistics a) {
		constructorCache.putUnsafe(in, out);
		if (out != null) {
			out.setStats(a);
		}
		return out;
	}
	
	private Expression liftRec(IConstructor tree) {
		Expression cached = constructorCache.get(tree);
		if (cached != null) {
			return cached;
		}
		
		ASTStatistics stats = new ASTStatistics();
		Expression result;
		
		if (TreeAdapter.isAppl(tree)) {
			String cons = TreeAdapter.getConstructorName(tree);
			
			if (cons != null && (cons.equals("MetaVariable") || cons.equals("TypedMetaVariable"))) {
				result = liftVariable(tree);
				stats.setNestedMetaVariables(1);
				return stats(tree, result, stats);
			}

			boolean lex = TreeAdapter.isLexical(tree);
			boolean inj = TreeAdapter.isInjectionOrSingleton(tree);
			boolean star = TreeAdapter.isNonEmptyStarList(tree);
			
			if (!lex || inj) {
				stats.setInjections(1);
			}
			else if (star) {
				stats.setInjections(1);
			}
			else {
				stats.setInjections(0);
			}
				
			
			IList args = TreeAdapter.getArgs(tree);
			java.util.List<Expression> kids = new ArrayList<Expression>(args.length());
			for (IValue arg : args) {
				Expression ast = liftRec((IConstructor) arg);
				if (ast == null) {
					return null;
				}
				kids.add(ast);
				stats.add(ast.getStats());
			}

			if (TreeAdapter.isLexical(tree)) {
				return stats(tree, new Tree.Lexical(tree, kids), stats);
			}
			else if (TreeAdapter.isList(tree)) {
				// TODO: splice element lists (can happen in case of ambiguous lists)
				return stats(tree, new Tree.List(tree, kids), stats);
			}
			else if (TreeAdapter.isOpt(tree)) {
				return stats(tree, new Tree.Optional(tree, kids), stats);
			}
			else { 
				return stats(tree, new Tree.Appl(tree, kids), stats);
			}
		}
		else if (TreeAdapter.isAmb(tree)) {
			ISet args = TreeAdapter.getAlternatives(tree);
			java.util.List<Expression> kids = new ArrayList<Expression>(args.size());
			
			ASTStatistics ref = null;
			
			for (IValue arg : args) {
				Expression ast = liftRec((IConstructor) arg);
				if (ast != null) {
					if (ref == null) {
						ref = ast.getStats();
						kids.add(ast);
					}
					else {
						ref = filter(kids, ast, ref);
					}
				}
			}
			
			if (kids.size() == 0) {
				return null;
			}
			
			stats = ref != null ? ref : new ASTStatistics();
			if (kids.size() == 1) {
				return kids.get(0);
			}
			
			stats.setAmbiguous(true);
			return stats(tree, new Tree.Amb(tree, kids), stats);
		}
		else {
			if (!TreeAdapter.isChar(tree)) {
				throw new ImplementationError("unexpected tree type: " + tree);
			}
			return stats(tree, new Tree.Char(tree), new ASTStatistics()); 
		}
	}

	private Expression liftVariable(IConstructor tree) {
		String cons = TreeAdapter.getConstructorName(tree);
		
		if (cons.equals("MetaVariable")) {
			IConstructor arg = (IConstructor) getASTArgs(tree).get(0);
			
			if (arg.getConstructorType() == Factory.Tree_Amb) {
				return filterNestedPattern(tree, arg); 
			}
			Expression result = (Expression) buildValue(arg);
		
			if (result != null && correctlyNestedPattern(TreeAdapter.getType(tree), result)) {
				return result;
			}
			return null;
		}
		throw new ImplementationError("Unexpected meta variable while lifting pattern");
	}


	private boolean correctlyNestedPattern(IConstructor expected, Expression exp) {
		if (exp.isTypedVariable()) {
			IConstructor expressionType = Symbols.typeToSymbol(exp.getType());

			// the declared type inside the pattern must match the produced type outside the brackets
			// "<" Pattern ">" -> STAT in the grammar and "<STAT t>" in the pattern. STAT == STAT.
			if (SymbolAdapter.isEqual(expressionType, expected)) {
				return true;
			}
			
			if (SymbolAdapter.isAnyList(expressionType) || SymbolAdapter.isOpt(expressionType)) {
				IConstructor elem = SymbolAdapter.getSymbol(expressionType);
				return SymbolAdapter.isEqual(elem, expected);
			}
		}else if (exp.isGuarded()) {
			IConstructor expressionType = Symbols.typeToSymbol(exp.getType());

			// the declared type inside the pattern must match the produced type outside the brackets
			// "<" [Type] Pattern ">" -> STAT in the grammar and "<[STAT] pattern>" in the pattern. STAT == STAT.
			return (SymbolAdapter.isEqual(expressionType, expected));
		}
		
		return true;
	}

	private IConstructor getConcretePattern(IConstructor tree) {
		String cons = TreeAdapter.getConstructorName(tree);
		
		if (cons.equals("ConcreteQuoted")) {
			return (IConstructor) getASTArgs(tree).get(0);
		}
		
		if (cons.equals("ConcreteTypedQuoted")) {
			 return (IConstructor) TreeAdapter.getArgs(tree).get(8);
		}
		
		throw new ImplementationError("Unexpected embedding syntax");
	}

	private IList getASTArgs(IConstructor tree) {
//		if (!TreeAdapter.isContextFree(tree)) {
//			throw new ImplementationError("This is not a context-free production: "
//					+ tree);
//		}
	
		IList children = TreeAdapter.getArgs(tree);
		IListWriter writer = Factory.Args.writer(ValueFactoryFactory.getValueFactory());
	
		for (int i = 0; i < children.length(); i++) {
			IConstructor kid = (IConstructor) children.get(i);
			if (!TreeAdapter.isLiteral(kid) && !TreeAdapter.isCILiteral(kid) && !isRascalLiteral(kid) && !TreeAdapter.isEmpty(kid)) {
				writer.append(kid);	
			} 
			// skip layout
			i++;
		}
		
		return writer.done();
	}

	private String sortName(IConstructor tree) {
		if (TreeAdapter.isAppl(tree)) {
			String sortName = TreeAdapter.getSortName(tree);
			
			if (isRascalSort(sortName)) {
				sortName = sortName.substring(1);
			}
			
			return sortName;
		}
		if (TreeAdapter.isAmb(tree)) {
			// all alternatives in an amb cluster have the same sort
			return sortName((IConstructor) TreeAdapter.getAlternatives(tree).iterator().next());
		}
		return "";
	}

	private String capitalize(String sort) {
		if (sort.length() == 0) {
			return sort;
		}
		if (sort.length() > 1) {
			return Character.toUpperCase(sort.charAt(0)) + sort.substring(1);
		}
		
		return sort.toUpperCase();
	}

	private static ImplementationError unexpectedError(Throwable e) {
		return new ImplementationError("Unexpected error in AST construction: " + e, e);
	}

	private boolean isEmbedding(IConstructor tree) {
		String name = TreeAdapter.getConstructorName(tree);
		return name.equals("ConcreteQuoted") 
//		|| name.equals("ConcreteUnquoted") 
		|| name.equals("ConcreteTypedQuoted");
	}

	private boolean isLexical(IConstructor tree) {
		if (TreeAdapter.isRascalLexical(tree)) {
			return true;
		}
		return false;
	}

	private boolean isRascalLiteral(IConstructor tree) {
		if (TreeAdapter.isAppl(tree)) {
			IConstructor prod = TreeAdapter.getProduction(tree);
			IConstructor rhs = ProductionAdapter.getType(prod);
			
			if (SymbolAdapter.isParameterizedSort(rhs) && SymbolAdapter.getName(rhs).equals("_WrappedLiteral")) {
				return true;
			}
		}
		return false;
	}

	private boolean isRascalSort(String sort) {
		return sort.startsWith(RASCAL_SORT_PREFIX);
	}
	
	private static AbstractAST callMakerMethod(String sort, String cons, Class<?> formals[], IConstructor src, Object actuals[]) {
		try {
			String name = sort + "$" + cons;
			Class<?> clazz = astClasses.get(name);
			
			if (clazz == null) {
				try {
					clazz = classLoader.loadClass("org.rascalmpl.semantics.dynamic." + name);
				}
				catch (ClassNotFoundException e) {
					// it happens
				}
				if (clazz == null) {
					clazz = classLoader.loadClass("org.rascalmpl.ast." + name);
				}
				if (clazz == null) {
					throw new ImplementationError("can not find AST class for " + name);
				}
				astClasses.put(name, clazz);
			}

			Class<?>[] realForms = new Class<?>[formals.length + 1];
			realForms[0] = IConstructor.class;
			System.arraycopy(formals, 0, realForms, 1, formals.length);
			Constructor<?> make = clazz.getConstructor(realForms);
			Object[] params = new Object[actuals.length + 1];
			params[0] = src;
			System.arraycopy(actuals, 0, params, 1, actuals.length);
			return (AbstractAST) make.newInstance(params);
		} catch (SecurityException e) {
			throw unexpectedError(e);
		} catch (NoSuchMethodException e) {
			throw unexpectedError(e);
		} catch (IllegalArgumentException e) {
			throw unexpectedError(e);
		} catch (IllegalAccessException e) {
			throw unexpectedError(e);
		} catch (InvocationTargetException e) {
			throw unexpectedError(e);
		} catch (ClassNotFoundException e) {
			throw unexpectedError(e);
		} catch (InstantiationException e) {
			throw unexpectedError(e);
		}
	}

}
