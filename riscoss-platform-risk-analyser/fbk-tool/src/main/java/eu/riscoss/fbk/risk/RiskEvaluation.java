package eu.riscoss.fbk.risk;

import java.io.PrintStream;
import java.util.Iterator;

import javax.script.ScriptException;

import eu.riscoss.fbk.language.Analysis;
import eu.riscoss.fbk.language.Program;
import eu.riscoss.fbk.language.Proposition;
import eu.riscoss.fbk.language.Relation;
import eu.riscoss.fbk.language.Result;
import eu.riscoss.fbk.language.Scenario.Pair;
import eu.riscoss.fbk.language.Solution;
import eu.riscoss.fbk.lp.Chunk;
import eu.riscoss.fbk.lp.FunctionsLibrary;
import eu.riscoss.fbk.lp.JsExtension;
import eu.riscoss.fbk.lp.LPKB;
import eu.riscoss.fbk.lp.Label;
import eu.riscoss.fbk.lp.Node;
import eu.riscoss.fbk.lp.Solver;
import eu.riscoss.fbk.semantics.Axiom;
import eu.riscoss.fbk.semantics.Condition;
import eu.riscoss.fbk.semantics.Rule;
import eu.riscoss.fbk.semantics.Semantics;
import eu.riscoss.fbk.sysex.WorkingDirectory;
import eu.riscoss.reasoner.Evidence;

public class RiskEvaluation implements Iterable<Solution>, Analysis
{
	Program program;
	
	public LPKB kb = new LPKB();
	
	Semantics semantics = new RiskSemantics();
	
	public RiskEvaluation() {
	}
	
	public void setProgram(Program program) {
		this.program = program;
	}
	
	public void run(WorkingDirectory dir) {
		run(program);
	}
	
	public void run(Program program) {
		
		JsExtension.get().put( "fx", FunctionsLibrary.get() );
		JsExtension.get().put( "program", program );
		
		setProgram(program);
		
		try {
			JsExtension.get().eval( program.getOptions().getValue( "code", "" ) );
		} catch (ScriptException e) {
			e.printStackTrace();
		}
		
		kb = mkgraph();
		
		kb.getGraph().propagate();
	}
	
	LPKB mkgraph() {
		LPKB kb = new LPKB();
		
		for (String type : program.getModel().propositionTypes()) {
			for (Proposition p : program.getModel().propositions(type)) {
				if (semantics.getAxiomCount(type) > 0) {
					for (Axiom a : semantics.axioms(type)) {
						
						eu.riscoss.fbk.lp.Relation r_comb = new eu.riscoss.fbk.lp.Relation();
						
						switch( a.getPropagationType() ) {
						case SAT_SAT:
							r_comb.setSatSolver( new Solver.AndSolver( false ) );
							r_comb.setDenSolver( new Solver.NoSolver( false ) );
							break;
						case SAT_DEN:
							r_comb.setSatSolver( new Solver.NoSolver( false ) ); // no
							r_comb.setDenSolver( new Solver.AndSolver( false ) );
							break;
						case DEN_SAT:
							r_comb.setSatSolver( new Solver.AndSolver( true ) );
							r_comb.setDenSolver( new Solver.NoSolver( false ) ); // no
							break;
						case DEN_DEN:
							r_comb.setSatSolver( new Solver.NoSolver( false ) ); // no
							r_comb.setDenSolver( new Solver.AndSolver( true ) );
							break;
						}
						
						Node target = kb.store(p, a.p_then);
						
						r_comb.setTargetNode(target);
						r_comb.setWeight( 1 );
						
						r_comb.setMnemonic(
								p.getId() + " -> " + a.getPropagationType().toString() + a.p_then );
						
						for (Condition cond : a.conditions()) {
							Node source = kb.store(p, cond.getPredicate());
							r_comb.addSourceNode(source);
						}
						
						kb.addRelation(r_comb);
						
						r_comb.informNodes();
					}
				} else {
					kb.store(p, "st");
				}
				
				for (Pair pair : program.getScenario().constraintsOf(p.getId())) {
					Node node = kb.getNode(p.getId(), pair.label);
					
					if (node == null) {
						continue;
					}
					
					float value = 1f;
					
					if (pair.value != null) {
						try {
							value = acquireValue(pair.value);
						} catch (Exception ex) {
							System.err.println("\"" + pair.value + "\" is not a real in the range [0,1]");
						}
					}
					
					String function = p.getProperty("function", null);
					
					if (function != null) {
						try {
							String code = "x=" + value + ";" + function;
							
							String result = JsExtension.get().eval(code).toString();
							
							value = Float.parseFloat(result);
						} catch (Exception ex) {
							ex.printStackTrace();
						}
					}
					
					node.setSatLabel(new Label(value, false));
				}
			}
		}
		
		for (String type : program.getModel().relationTypes()) {
			for (Relation r : program.getModel().relations(type)) {
				if( r.getStereotype().indexOf( "expose" ) != -1 )
					System.out.print("");
				
				for (Rule rule : semantics.rules.list(type)) {
					
					Node target = kb.store(r.getTarget(), rule.targetPred);
					
					float w = getWeight(r);
					
					w = Math.abs( w );
					
					eu.riscoss.fbk.lp.Relation rel = new eu.riscoss.fbk.lp.Relation();
					
					rel.setWeight( w );
					
					if( r.getProperty( "function", null ) != null ) {
						rel.setJs( r.getProperty( "function", "" ) );
					}
					switch( rule.getPropagationType() ) {
					case SAT_SAT:
						rel.setSatSolver( new Solver.AndSolver( false ) );
						rel.setDenSolver( new Solver.NoSolver( false ) ); // no
						break;
					case SAT_DEN:
						rel.setSatSolver( new Solver.NoSolver( false ) ); // no
						rel.setDenSolver( new Solver.AndSolver( false ) );
						break;
					case DEN_SAT:
						rel.setSatSolver( new Solver.AndSolver( true ) );
						rel.setDenSolver( new Solver.NoSolver( false ) ); // no
						break;
					case DEN_DEN:
						rel.setSatSolver( new Solver.NoSolver( false ) ); // no
						rel.setDenSolver( new Solver.AndSolver( true ) );
						break;
					}
					rel.setTargetNode(target);
					rel.setMnemonic(
							rule.getPropagationType().toString() + r.getSources() + " -" + type + "(" + w + ")-> " + r.getTarget());
					
					for (int i = 0; i < r.getSourceCount(); i++) {
						Node source = kb.store(r.getSources().get(i), rule.getSourcePred());
						
						rel.addSourceNode(source);
					}
					
					kb.addRelation(rel);
					
					rel.informNodes();
				}
			}
		}
		
		return kb;
	}
	
	private float getWeight(Relation r) {
		return r.getWeight();
	}
	
	private float acquireValue(String value) throws Exception {
		try {
			return Float.parseFloat(value);
		} catch (Exception ex) {
			if (value == null) {
				throw ex;
			}
			
			if (value.equals("st")) {
				return 1;
			}
			if (value.endsWith("sat")) {
				return 1;
			}
		}
		
		return 0;
	}
	
	public void printSolutions(PrintStream out) {
		for (String id : kb.index()) {
			Chunk c = kb.index().getChunk(id);
			
			for (String pred : c.predicates()) {
				Node node = kb.index().getNode(id, pred);
				
				out.println(
						c.getProposition().getId() + "." + pred + ": " +
								node.getSatLabel().getValue() + ", " +
								node.getDenLabel().getValue());
			}
		}
	}
	
	class SolutionIterator implements Iterator<Solution> {
		boolean done = false;
		
		@Override
		public boolean hasNext()
		{
			return done == false;
		}
		
		@Override
		public Solution next()
		{
			
			Solution sol = new Solution();
			
			for (Proposition p : program.getModel().propositions()) {
				Chunk c = kb.index().getChunk(p.getId());
				
				if (c != null) {
					for (String pred : c.predicates()) {
						Node node = c.getPredicate(pred);
						sol.addValue(
								c.getProposition().getProperty("name",
										c.getProposition().getId()),
										//                                        c.getProposition().getProperty("label", c.getProposition().getId())),
										pred,
										"" + ((Label) node.getSatLabel()).getValue());
						sol.addValue(
								"-" + c.getProposition().getProperty("name",
										c.getProposition().getId()),
										pred,
										"" + ((Label) node.getDenLabel()).getValue());
					}
				}
			}
			
			done = true;
			
			return sol;
		}
		
		@Override
		public void remove()
		{
		}
	}
	
	public double getPositiveValue( String id ) {
		try {
			Proposition p = program.getModel().getProposition( id );
			Chunk c = kb.index().getChunk(p.getId());
			
			Node node = c.getPredicate( "output" );
			return ((Label) node.getSatLabel()).getValue();
		}
		catch( Exception ex ) {
			ex.printStackTrace();
			return 0;
		}
	}
	
	public Evidence getEvidence( String id ) {
		try {
			Proposition p = program.getModel().getProposition( id );
			Chunk c = kb.index().getChunk(p.getId());
			Node node = c.getPredicate( "output" );
			return new Evidence( node.getSatLabel().getValue(), node.getDenLabel().getValue() );
		}
		catch( Exception ex ) {
//			ex.printStackTrace();
			return new Evidence( 0, 0 );
		}
	}
	
	public double getNegativeValue( String id ) {
		try {
			Proposition p = program.getModel().getProposition( id );
			Chunk c = kb.index().getChunk(p.getId());
			Node node = c.getPredicate( "output" );
			return ((Label) node.getDenLabel()).getValue();
		}
		catch( Exception ex ) {
			ex.printStackTrace();
			return 0;
		}
	}
	
	public Evidence getValue( String id, String predicate ) {
		try {
			Proposition p = program.getModel().getProposition( id );
			Chunk c = kb.index().getChunk(p.getId());
			Node node = c.getPredicate(predicate );
			if( node == null ) return null;
			return new Evidence(
					((Label) node.getSatLabel()).getValue(), 
					((Label) node.getDenLabel()).getValue() );
		}
		catch( Exception ex ) {
			ex.printStackTrace();
			return null;
		}
	}
	
	@Override
	public Iterator<Solution> iterator() {
		return new SolutionIterator();
	}
	
	@Override
	public Result getResult() {
		return new Result()
		{
			@Override
			public String getDescription()
			{
				return "";
			}
			
			@Override
			public Iterable<Solution> solutions()
			{
				return RiskEvaluation.this;
			}
		};
	}
}
