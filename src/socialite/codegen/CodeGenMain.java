package socialite.codegen;

import gnu.trove.list.array.TIntArrayList;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import socialite.engine.Config;
import socialite.eval.Eval;
import socialite.eval.EvalDist;
import socialite.eval.EvalParallel;
import socialite.parser.Const;
import socialite.parser.GeneratedT;
import socialite.parser.Predicate;
import socialite.parser.PrivateTable;
import socialite.parser.Query;
import socialite.parser.Rule;
import socialite.parser.Table;
import socialite.resource.SRuntime;
import socialite.resource.TableInstRegistry;
import socialite.tables.QueryRunnable;
import socialite.tables.QueryVisitor;
import socialite.tables.TableInst;
import socialite.util.InternalException;
import socialite.util.Loader;
import socialite.util.SociaLiteException;


public class CodeGenMain {
	static final Log L=LogFactory.getLog(CodeGenMain.class);
	
	static CodeClassCache visitorClass$ = new CodeClassCache();
	static CodeClassCache arrayInitClass$ = new CodeClassCache();
	static QueryCache<Class> queryClass$ = new QueryCache<Class>();
	public static void clearCache() {
		visitorClass$.clear();
		queryClass$.clear();
		arrayInitClass$.clear();
	}
	public static void maybeClearCache() {
		visitorClass$.clearIfLarge();
		queryClass$.clearIfLarge();
		arrayInitClass$.clearIfLarge();
	}
	
	List<Epoch> epochs;
	List<Table> newTables;	
	Query query;
	Analysis an;
	Config conf;
	
	List<Eval> evalInsts=new ArrayList<Eval>();
	List<Class> generated = new ArrayList<Class>();
	
	SRuntime runtime;
	Map<String, Table> tableMap;
	
	Class<? extends Runnable> queryClass;
	QueryVisitor queryVisitor;
	
	protected CodeGenMain() {}
	public CodeGenMain(Config _conf, Analysis _an, SRuntime rt) {
		CodeGenMain.maybeClearCache();
		
		conf = _conf;
		runtime = rt;
		an = _an;
		evalInsts = new ArrayList<Eval>();		
		epochs = an.getEpochs();
		newTables = an.getNewTables();
		
		query = an.getQuery();
		
		runtime.updateTableMap(an.getTableMap());
		tableMap = runtime.getTableMap();		
	}
	
	public Set<Table> getReads() {
		return an.getReads();
	}
	public Set<Table> getWrites() {
		return an.getWrites();
	}
	public SRuntime getRuntime() { return runtime; }
	
	public List<Rule> getRules() { return an.getRules(); }
	public TableInst[] getTableArray(String name) {
		Table t = tableMap.get(name);
		if (t instanceof PrivateTable) return null;
		TableInstRegistry reg = runtime.getTableRegistry();
		return reg.getTableInstArray(t.id());
	}
		
	Eval getEvalInstance(Epoch e) {
		return runtime.getEvalInst(e);
	}

	void addToGeneratedClasses(Class klass) {
		for (Class innerClass:klass.getDeclaredClasses()) 
			generated.add(innerClass);
		generated.add(klass);		
	}
	void addToGeneratedClasses(Collection<Class> klasses) {
		for (Class klass:klasses)
			addToGeneratedClasses(klass);
	}
	public void generate() {
		assert evalInsts.size()==0;
		
		generateVisitorBase();
		
		addToGeneratedClasses(TupleCodeGen.generate(conf, an.getRules(), tableMap));
		
		List<Table> newTablesToGen = new ArrayList<Table>(newTables);
		newTablesToGen.retainAll(an.getReads());
		
		try {
			addToGeneratedClasses(TableCodeGen.ensureExist(conf, newTablesToGen));
			addToGeneratedClasses(TableCodeGen.ensureExist(conf, an.getDeltaTables()));
			addToGeneratedClasses(TableCodeGen.ensureExist(conf, an.getRemoteTables()));
			addToGeneratedClasses(TableCodeGen.ensureExist(conf, an.getPrivateTables()));
		} catch (InternalException e) { throw new SociaLiteException(e); }
		
		for (Epoch e:epochs) {
			generateVisitors(e);
			generateArrayInit(e);
			Class evalClass=generateEval(e);
			if (!evalClass.equals(EvalParallel.class) && 
					!evalClass.equals(EvalDist.class)) {
				addToGeneratedClasses(evalClass);
			}
			e.setEvalClass(evalClass);
		}		
		prepareRuntime();
	}
	void prepareRuntime() {
		for (Epoch e:epochs) {
			runtime.update(e);
		}
	}
	public List<Class> getGeneratedClasses() { return generated; }
	
	public List<Epoch> getEpoch() { return epochs; }
	
	public List<Eval> getEvalInsts() {
		if (evalInsts.size()>0) return evalInsts;
		
		for (int i=0; i<epochs.size(); i++) {
			Epoch epoch=epochs.get(i);
			Eval eval=getEvalInstance(epoch);
			
			assert eval!=null:"eval is null";
			evalInsts.add(eval);
		}
		return evalInsts;
	}	
	
	void generateArrayInit(Epoch e) {
		if (!e.hasInitRule()) return;
		
		for (Rule r:e.getRules()) {
			if (!r.isSimpleArrayInit()) continue;
			
			String sig=r.signature(tableMap);
			Class<?> klass = arrayInitClass$.get(sig);
			if (klass==null) {
				InitCodeGen initGen = new InitCodeGen(r, tableMap, runtime, conf);
				Compiler c = new Compiler(conf);				
				boolean success = c.compile(initGen.name(), initGen.generate());
				if (!success) {
					String msg = "Error while compiling init code:"+c.getErrorMsg(); 
					L.error(msg);
					throw new SociaLiteException(msg);
				}
				klass = Loader.forName(initGen.name());
				arrayInitClass$.put(sig, klass);
				addToGeneratedClasses(klass);
			}			
			e.addInitClass(klass, r.getConsts());			
		}
	}
	
	Class generateEval(Epoch e) {
		//long s=System.currentTimeMillis();
		List<Table> newTablesToAlloc = new ArrayList<Table>();
		for (Table t:e.getNewTables()) {
			if (!(t instanceof GeneratedT))
				newTablesToAlloc.add(t);
		}
		newTablesToAlloc.retainAll(an.getReads());
		
		if (newTablesToAlloc.isEmpty()) {
			if (conf.isDistributed()) {
				return EvalDist.class;
			} else {
				assert conf.isParallel() || conf.isSequential();
				return EvalParallel.class;
			}			
		}		
		EvalCodeGen evalGen = new EvalCodeGen(e, newTablesToAlloc, tableMap, conf);
		Compiler c = new Compiler(conf);
		if (conf.getDebugOpt("GenerateEval")) {
			boolean success = c.compile(evalGen.evalName(), evalGen.generate());
			if (!success) {
				String msg = "Error while compiling eval class:"+c.getErrorMsg(); 
				L.error(msg);
				throw new SociaLiteException(msg);
			}
		}
		Class<? extends Runnable> evalClass=Loader.forName(evalGen.evalName());
		return evalClass;
	}
	
	public TableInstRegistry getTableRegistry() {		
		return runtime.getTableRegistry(); 
	}
		
	public Class getQueryClass() {
		return queryClass;
	}
	
	public Runnable getQueryInst() {
		if (queryClass==null || query==null || queryVisitor==null)
			return null;

		Predicate p=query.getP();
		List args = p.getConstValues();
		
		Table t=tableMap.get(p.name());		
		String queryClsName=queryClass.getName();		
		QueryRunnable qr = runtime.getQueryInst(t.id(), queryClsName, queryVisitor);
		qr.setArgs(args);
		qr.setQueryVisitor(queryVisitor);
		//queryInst$.put(p, qr);
		return qr;
	}
	
	public Query getQuery() { return query; }
	
	public void generateQuery(QueryVisitor qv) {
		if (query==null) return;
		
		queryVisitor = qv;
		queryClass = queryClass$.get(query.getP(), tableMap);
		if (queryClass!=null) return;		
		Table queryT = tableMap.get(query.getP().name());
		if (!queryT.isCompiled()) {
			queryClass=null;
			return;
		}
		
		QueryCodeGen qgen = new QueryCodeGen(query, tableMap, qv, conf.isSequential());
		Compiler c = new Compiler(conf);
		boolean success=c.compile(qgen.queryName(), qgen.generate());
		if (!success) {
			String msg="Error while compiling a query class:"+c.getErrorMsg();
			throw new SociaLiteException(msg);
		}
		queryClass=Loader.forName(qgen.queryName());
		queryClass$.put(query.getP(), tableMap, queryClass);
		addToGeneratedClasses(queryClass);
	}	
	
	void generateVisitorBase() {		
		generated.addAll(VisitorBaseGen.generate(conf, newTables));
		generated.addAll(VisitorBaseGen.generate(conf, an.getRemoteTables()));			
	}
	
	void generateVisitors(Epoch e) {		
		Iterator<RuleComp> it = e.getRuleComps().iterator();
		while(it.hasNext()) {
			RuleComp rc=it.next();			
			for (Rule r:rc.getRules()) {
				if (r.isSimpleArrayInit()) continue;
				genVisitor(e, r);
			}
		}
	}
	
	boolean isFirstTableEmpty(Rule r) {
		Object body1=r.getBody().get(0);
		if (body1 instanceof Predicate) {
			Predicate p=(Predicate)body1;
			Table t=tableMap.get(p.name());
			if (newTables.contains(t))
				return true;
		} 
		return false;
	}
		
	void genVisitor(Epoch e, Rule r) {
		String sig=r.signature(tableMap);
		Class<?> klass = visitorClass$.get(sig);
		boolean is_generated=false;
		if (klass==null) {
			VisitorCodeGen vgen = new VisitorCodeGen(e, r, tableMap, conf);
			if (conf.getDebugOpt("GenerateVisitor")) {
				Compiler c = new Compiler(conf);
				boolean success=c.compile(vgen.visitorName(), vgen.generate());				
				if (!success) {
					String msg="Error while compiling visitor class:"+c.getErrorMsg();
					throw new SociaLiteException(msg);
				}
				is_generated=true;
			}
			klass=Loader.forName(vgen.visitorName());
			visitorClass$.put(r.signature(tableMap), klass);
			if (is_generated) { addToGeneratedClasses(klass); }
		}
		e.addVisitorClass(r.id(), klass);		
	}
}


class QuerySignature {
	String name;
	TIntArrayList constIdx;
	public QuerySignature(String _name, TIntArrayList _constIdx) {
		name = _name;
		constIdx = _constIdx;
	}
	public int hashCode() { return name.hashCode() ^ constIdx.hashCode(); }
	public boolean equals(Object o) {
		if (!(o instanceof QuerySignature)) return false;
		
		QuerySignature sig = (QuerySignature)o;
		return name.equals(sig.name) && constIdx.equals(sig.constIdx);
	}
	public QuerySignature clone() {
		return new QuerySignature(name, new TIntArrayList(constIdx));
	}
}

class CodeClassCache {
	Map<String, Class> classMap;
	int size=0;
	CodeClassCache() {
		classMap = new HashMap<String, Class>();
	}
	public synchronized void clear() {
		classMap.clear();
		size=0;
	}
	public synchronized void clearIfLarge() {
		if (size>1024*128) {
			classMap.clear();
			size=0;
		}
	}
	public synchronized Class get(String ruleSig) {			
		return classMap.get(ruleSig);
	}
	public synchronized void put(String ruleSig, Class cls) {
		Class old = classMap.put(ruleSig, cls);			
		if (old==null) size++;
	}
}
class QueryCache<T> {
	int size=0;
	Map<QuerySignature, T> queryMap;
	QuerySignature sig$ = new QuerySignature("tmp-name", new TIntArrayList());
	QueryCache() {
		queryMap = new HashMap<QuerySignature, T>();
		size=0;
	}
	
	public synchronized void clear() {
		queryMap.clear();
		size=0;
	}
	public synchronized void clearIfLarge() {
		if (size > 1024*128) {
			queryMap.clear();
			size=0;
		}
	}
	public synchronized T get(Predicate queryP, Map<String, Table> tableMap) {
		sig$ = getQuerySig(queryP, tableMap);
		return queryMap.get(sig$);
	}
	public synchronized void put(Predicate queryP, Map<String, Table> tableMap, T query) {
		QuerySignature querySig = getNewQuerySig(queryP, tableMap);
		T old = queryMap.put(querySig, query);
		if (old==null) size++;
	}
	QuerySignature getNewQuerySig(Predicate p, Map<String, Table> tableMap) {
		return getQuerySig(p, tableMap).clone();
	}
	QuerySignature getQuerySig(Predicate p, Map<String, Table> tableMap) {
		sig$.name = tableMap.get(p.name()).className();
		//sig$.name = p.name();
		sig$.constIdx.resetQuick();
		int i=0;
		for (Object o:p.getAllParamsExpanded()) {
			if (o instanceof Const)
				sig$.constIdx.add(i);
			i++;
		}
		return sig$;
	}
}