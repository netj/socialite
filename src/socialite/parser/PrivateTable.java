package socialite.parser;

import java.util.ArrayList;
import java.util.List;

import socialite.parser.antlr.ColumnGroup;
import socialite.parser.antlr.IndexBy;
import socialite.parser.antlr.TableDecl;
import socialite.parser.antlr.TableOpt;
import socialite.parser.antlr.ColumnDecl;
import socialite.parser.antlr.ColOpt;
import socialite.parser.antlr.ColRange;
import socialite.parser.antlr.ColSize;
import socialite.util.AnalysisException;
import socialite.util.InternalException;
import gnu.trove.list.array.TIntArrayList;

public class PrivateTable extends Table implements GeneratedT {
	int origId;
	Table origT;
	Rule rule;
	public PrivateTable(Table t, Rule _r) { 
		super(genDecl(t, _r));
		ConstCols.init(this, _r.getHead());
		
		origT=t;
		origId=t.id();
		rule = _r;
		
		className = signature();
		
		maybeSetArrayBaseAndSize();
		
		try {
			setGroupByColNum(t.groupbyColNum());
		} catch (InternalException e) {
			throw new AnalysisException(e.getMessage(), _r);
		}		
	}
	
	public String signature() {
		if (rule==null) return "not-initialized";
		
		String sig=defaultsig();
		
		for (int c:ConstCols.get(rule.getHead()))
			sig += "_const"+c;
		return sig+"_id"+origId;
	}
	
	public String origName() { return super.name(); }
	
	@Override public Table origT() { return origT; }
	@Override public int origId() { return origId; }

	static TableDecl genDecl(Table t, Rule r) {
		assert !t.hasNestedT();
		
		String newName = name(t, r);
		
		List<ColumnDecl> colDecls = new ArrayList<ColumnDecl>();
		int columns=0;
		for (int i=0; i<t.numColumns(); i++) {
			Column c=t.getColumn(i);
			String colname = "col"+(columns++);
			ColumnDecl decl =new ColumnDecl(c.type(), colname);
			colDecls.add(decl);
			if (i==0) {
				if (c.hasRange()) {
					int[] _r=c.getRange();
					decl.setOption(new ColRange(_r[0], _r[1]));
				} else if (c.hasSize()) {
					decl.setOption(new ColSize(c.getSize()));
				}
			}
		}
		TableDecl decl=new TableDecl(newName, null, colDecls, null);		
		
		if (t.indexedCols().length>0) {
			int col=t.indexedCols()[0];
			List<TableOpt> opts=new ArrayList<TableOpt>();
			opts.add(new IndexBy("col"+col));
			decl.setOptions(opts);
		} else if (t.sortbyCols().length>0) {
			int col=t.sortbyCols()[0];
			List<TableOpt> opts=new ArrayList<TableOpt>();
			opts.add(new IndexBy("col"+col));
			decl.setOptions(opts);
		}
		return decl;
	}
	
	static String constColumns(Predicate head) {
		String s="";
		int idx=0;
		if (head.idxParam!=null) {
			if (head.idxParam instanceof Const)
				s+="_"+idx;
			idx++;
		}
		for (Object o:head.params) {
			if (o instanceof Const)
				s+="_"+idx;
			idx++;
		}
		return s;
	}
	public static String name(Table origT, Rule rule) {
		String name = "Private_"+origT.name();
		int[] constIdx = ConstCols.get(rule.getHead());
		for (int i:constIdx) {
			name += "_"+i;
		}
		return name;
	}
}
