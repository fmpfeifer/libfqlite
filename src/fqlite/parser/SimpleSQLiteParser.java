package fqlite.parser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;
import java.util.regex.Pattern;

import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.ConsoleErrorListener;
import org.antlr.v4.runtime.tree.ParseTree;
import org.antlr.v4.runtime.tree.ParseTreeWalker;

import fqlite.base.Base;
import fqlite.descriptor.IndexDescriptor;
import fqlite.descriptor.TableDescriptor;
import fqlite.pattern.HeaderPattern;
import fqlite.pattern.IntegerConstraint;
import fqlite.util.Logger;


/**
 * With the help of the present class CREATE TABLE and CREATE INDEX statements can be parsed and decomposed. 
 * 
 * Note: ANTLR (ANother Tool for Language Recognition) is a powerful parser generator for 
 * reading, processing, executing, or translating structured text or binary files. 
 * 
 * This class builds up on the parser classes of ANTLR-library.
 * 
 * 
 * @author pawlaszc
 * 
 * @version 1.0
 *
 */
public class SimpleSQLiteParser extends Base {

	/* determine data type */

	/* INT */
	static String[] inttypes = { "INT", "INTEGER", "INTUNSIGNED", "INTSIGNED", "LONG", "TINYINT", "SMALLINT", "MEDIUMINT", "BIGINT",
			"UNSIGNEDBIGINT", "INT2", "INT8"};
	/* TEXT */
	static String[] texttypes = { "TEXT", "CHARACTER", "CLOB", "VARCHAR", "VARYINGCHARACTER", "NCHAR",
			"NATIVE CHARACTER", "NVARCHAR" };
	/* BLOB */
	static String[] blobtype = { "BLOB" };
	
	/* REAL */
	static String[] realtype = { "REAL", "DOUBLE", "DOUBLEPRECISION", "FLOAT" };

	/* could by any of the above STORAGE class */
	static String[] numerictype = { "NUMERIC", "DECIMAL", "BOOLEAN", "DATE", "DATETIME" };
	
	
	
	SQLiteLexer lexer;
	String tablename = null;
	String modulname = null;
	
	/* prepare data fields */
	List<String> coltypes = new ArrayList<String>();
	List<String> sqltypes = new ArrayList<String>();
	List<String> colconstraints = new ArrayList<String>();
	List<String> colnames = new ArrayList<String>();
	List<String> tableconstraint = new ArrayList<String>();
	/* indices of the columns without a (known) type name */
	java.util.Set<Integer> untypedColumns = new java.util.HashSet<Integer>();
	Map<Integer,String> constraints = new HashMap<Integer,String>();
	HeaderPattern pattern = new HeaderPattern();

	TableDescriptor tds  = null;
	IndexDescriptor ids  = null;
	int column;
	
	
	/**
	 * Call this method to parse the SQL-statement CREATE TABLE.
	 * The result will be a TableDescriptor object, that contains component name, column names 
	 * an types. This information is necessary for matching the data records.
	 * 
	 * Example statement would look like this:
	 * 	
	 * CREATE TABLE 'users' (
	 *		'name' TEXT,
	 *		'surname' TEXT,
     *		'lastUpdate' TEXT
	 *	);
	 * 
	 * @param stmt The statement to be parsed.
	 * @return a TableDescriptor Object with all the information about the component. 
	 */
	public TableDescriptor parseTable(String stmt)
	{
		
		/* what kind of SQL statement ? */
		
		if ((stmt.indexOf("CREATE TABLE")) >= 0)
		{
			return parseCreateTable(stmt);
		}
		else if ((stmt.indexOf("CREATE TEMP TABLE")) >= 0)
		{
			Logger.out.debug("Found CREATE TEMP TABLE statement");
		}
		else if ((stmt.indexOf("CREATE VIRTUAL TABLE")) >= 0)
		{
			return parseCreateVirtualTable(stmt);	
		}
		
		return null;
		
	}
	
	public IndexDescriptor parseIndex(String stmt)
	{
		if (stmt.indexOf("CREATE INDEX ") >= 0 || stmt.indexOf("CREATE UNIQUE INDEX ") >= 0)
		{
			return parseCreateIndex(stmt);
		}
		
		return null;
	}
	
	
	
	
	
	private static final Pattern WITHOUT_ROWID = Pattern.compile("\\bWITHOUT\\s+ROWID\\b", Pattern.CASE_INSENSITIVE);

	/**
	 * @param stmt a CREATE TABLE statement
	 * @return true, if the table is declared as WITHOUT ROWID (key words are case insensitive)
	 */
	public static boolean isWithoutRowid(String stmt) {
		return stmt != null && WITHOUT_ROWID.matcher(stmt).find();
	}

	public String trim(String value)
	{
		if (value.startsWith("'") || value.startsWith("\""))
		{
			value = value.substring(1);
		
			if (value.endsWith("'") || value.endsWith("\""))
				value = value.substring(0,value.length()-1);
		
		}
		return value;
	}
	
	/**
	 * Parse a CREATE VIRTUAL TABLE statement and return a TableDescriptor object.
	 * 
	 * @param stmt String with the CREATE VIRTUAL TABLE statement to parse.
	 * @return the TableDescriptor object
	 */
	private TableDescriptor parseCreateVirtualTable(String stmt)
	{
		column = 0;
		modulname = null;
		
		// Create a lexer and parser for the input.
        SQLiteLexer lexer = new SQLiteLexer(CharStreams.fromString(stmt));
        lexer.removeErrorListener(ConsoleErrorListener.INSTANCE);
        SQLiteParser parser = new SQLiteParser(new CommonTokenStream(lexer));
        parser.removeErrorListener(ConsoleErrorListener.INSTANCE);

    
        // Invoke the `create_table_stmt` production.
        ParseTree tree = parser.create_virtual_table_stmt();

        /* let's walk through the syntax tree */
        ParseTreeWalker.DEFAULT.walk(new SQLiteBaseListener(){ 	
       
        /* callback methods */
        	
        	@Override public void enterTable_name(SQLiteParser.Table_nameContext ctx) 
        	{
        		tablename = ctx.getText();
        		tablename = trim(tablename);
        		if (tablename.length()==0)
        			tablename = "<no name>";
        		info("Tablename "  + tablename);
        	}
        	
        	
        	@Override public void enterModule_name(SQLiteParser.Module_nameContext ctx) 
        	{
        		 modulname = ctx.getText();
         		 info(" name " + modulname);
         		 if (modulname.equals("fts4")|| modulname.equals("fts3"))
         		 {
         			 info("Found FreeTextSearch Module (fts3/4)");
         		 }
         		 else if (modulname.equals("rtree"))
         		 {
         			 info("Found rtree Module");

         		 } 
         		 

        	}
        	
        	@Override public void enterModule_argument(SQLiteParser.Module_argumentContext ctx) 
        	{ 
        		String modulargument = ctx.getText();
        		info(" arg " + modulargument);
        		
        		/* RTree Modul ?*/
        		if("rtree".equalsIgnoreCase(modulname) || "rtree_i32".equalsIgnoreCase(modulname))
        		{
        			colnames.add(modulargument);
        			
        			if (column == 0)
        				coltypes.add("INT");
        			else
        				coltypes.add("NUMERIC");
        			column++;
        		}
        	}

        	
        	
        },tree);
	
		tds = new TableDescriptor(tablename,stmt,sqltypes,coltypes,colnames,colconstraints,tableconstraint,null,isWithoutRowid(stmt));
		
		tds.setVirtual(true);
		if (null!=modulname)
			tds.setModulname(modulname);
		return tds;
	}
	
	String idxname;
	
	private IndexDescriptor parseCreateIndex(String stmt)
	{
		column = 0;
	    // Create a lexer and parser for the input.
        SQLiteLexer lexer = new SQLiteLexer(CharStreams.fromString(stmt));
        lexer.removeErrorListener(ConsoleErrorListener.INSTANCE);
        SQLiteParser parser = new SQLiteParser(new CommonTokenStream(lexer));
        parser.removeErrorListener(ConsoleErrorListener.INSTANCE);

    
        // Invoke the `create_index_stmt` production.
        ParseTree tree = parser.create_index_stmt();
        
        /* let's walk through the syntax tree */
        ParseTreeWalker.DEFAULT.walk(new SQLiteBaseListener(){ 	
        	
        
        /**
    	 * {@inheritDoc}
    	 *
    	 * <p>The default implementation does nothing.</p>
    	 */
    	@Override public void enterIndex_name(SQLiteParser.Index_nameContext ctx) 
    	{ 
		
    		idxname = ctx.getText(); //"_IDX_" + ctx.getText();
        	
    		info("INDEX " + idxname);
        }  
    	
    	
    	/**
    	 * {@inheritDoc}
    	 *
    	 * <p>The default implementation does nothing.</p>
    	 */
    	@Override public void enterIndexed_column(SQLiteParser.Indexed_columnContext ctx) 
    	{
    		String colname = ctx.getText();
    		colname = trim(colname);
    		colnames.add(colname);
    		info("Columnname " + colname);		
    	}

    	@Override public void enterTable_name(SQLiteParser.Table_nameContext ctx) 
    	{
    		tablename = ctx.getText();
    		tablename = trim(tablename);
    		if (tablename.length()==0)
    			tablename = "<no name>";
    		info("Tablename "  + tablename);
    	}
    	
    	
    	
    	
        },tree);
		
        
		return new IndexDescriptor(idxname,tablename,stmt,colnames);
        
	}
	
	
	/**
	 * Parse a CREATE TABLE statement and return a TableDescriptor object.
	 * 
	 * @param stmt String with the CREATE TABLE statement to parse.
	 * @return the TableDescriptor object
	 */
	private TableDescriptor parseCreateTable(String stmt)
	{
		column = 0;
		
	    // Create a lexer and parser for the input.
        SQLiteLexer lexer = new SQLiteLexer(CharStreams.fromString(stmt));
        lexer.removeErrorListener(ConsoleErrorListener.INSTANCE);
        SQLiteParser parser = new SQLiteParser(new CommonTokenStream(lexer));
        parser.removeErrorListener(ConsoleErrorListener.INSTANCE);

    
        // Invoke the `create_table_stmt` production.
        ParseTree tree = parser.create_table_stmt();


        /* let's walk through the syntax tree */
        ParseTreeWalker.DEFAULT.walk(new SQLiteBaseListener(){ 	
        	
        	@Override public void enterTable_name(SQLiteParser.Table_nameContext ctx) 
        	{
        		isTableConstraint = false;
        		tablename = ctx.getText();
        		tablename = trim(tablename);
        		if (tablename.length()==0)
        			tablename = "<no name>";
        		info("Tablename "  + tablename);
        	}
        	
        	boolean tblconstraint = false;

        	
        	
        	@Override public void enterColumn_name(SQLiteParser.Column_nameContext ctx) 
        	{
        		if(inForeignTable)
        		{
        			inForeignTable = false;
        			return;
        		}
        		if(isTableConstraint)
        		{
        			return;
        		}
        		cons="";
        		
        		String colname = ctx.getText();
        		
        		info("enterColumn_name()::colname =" + colname);
        		
        		if (!colname.equals("CONSTRAINT"))
        		{
        			colname = trim(colname);
        			colnames.add(colname);
        			inColumnDef = true;
        			typeSeen = false;
        			
        			info("Columnname " + colname);
        		}
        		else
        		{
        			info("aha!!!");
        			tblconstraint = true;
        		}	
        		
        		
        	}
        	
        	boolean inForeignTable = false;
        	
        	/**
        	 * Enter a parse tree produced by {@link SQLiteParser#foreign_table}.
        	 * @param ctx the parse tree
        	 */
        	@Override public void enterForeign_table(SQLiteParser.Foreign_tableContext ctx)
        	{
        		info("Enter foreign Table!!!");
        		inForeignTable = true;
        	}
        	
        	@Override public void exitForeign_table(SQLiteParser.Foreign_tableContext ctx)
        	{
        		info("Exit foreign Table!!!");
        		
        	}
        	
        	
        	@Override public void enterType_name(SQLiteParser.Type_nameContext ctx) 
        	{ 
        		String value = ctx.getText();
        	    value = value.trim();
        		
        		/* the CONSTRAINT key word is mistakenly identified a type */
        		if (tblconstraint)
        		{
        			info("Table Constraint: " + value);
        			tableconstraint.add(value);
        			tblconstraint = false;
        		}
        		else
        		{
	        		sqltypes.add(value);
	        		info("SQLType::" + value);
	        		String type = getType(value);
	        		/* SQLite: a type name without any of the known key words has NUMERIC affinity */
	        		if (type.length() == 0) {
	        			type = "NUMERIC";
	        			untypedColumns.add(coltypes.size());
	        		}
	        		coltypes.add(type);
	        		typeSeen = true;
	        		info("Typename " + trim(type));
        		}
        		
        	}
        	
        	@Override public void enterKeyword(SQLiteParser.KeywordContext ctx)
        	{
        		info("Enter keyword ");
        	}
        	
        	@Override public void exitKeyword(SQLiteParser.KeywordContext ctx)
        	{
        		info("Exit keyword ");
            }
        	
        
        	String cons = "";
        	
        	@Override public void enterColumn_constraint(SQLiteParser.Column_constraintContext ctx)
        	{ 
        		String constraint = ctx.getText().toUpperCase();
        		
        		if (constraint.contains("NOTNULL"))
        			constraints.put(column,constraint);
        		
        	}
        	
        	@Override public void exitColumn_constraint(SQLiteParser.Column_constraintContext ctx)
        	{ 
        		String constraint = ctx.getText();
        		
        		info("Columnconstraint " + constraint);
        		cons += constraint.toUpperCase() + " ";
        	}
        	
        
        	
        	/* true while a column definition is parsed and whether it declared a type */
        	boolean inColumnDef = false;
        	boolean typeSeen = false;

        	@Override public void exitColumn_def(SQLiteParser.Column_defContext ctx) 
        	{ 
        		/*
        		 * a column without type name - keep the type lists aligned with the column names.
        		 * Such a column can hold values of any type -> NUMERIC affinity
        		 */
        		if (inColumnDef && !typeSeen)
        		{
        			sqltypes.add("");
        			untypedColumns.add(coltypes.size());
        			coltypes.add("NUMERIC");
        		}
        		inColumnDef = false;
       	        info("adding cons:" + cons);
        		colconstraints.add(cons);
        		column++;
        	}
        	
        	boolean isTableConstraint = false;

        	@Override public void enterTable_constraint(SQLiteParser.Table_constraintContext ctx) 
        	{ 
        		isTableConstraint = true;
        		info("Table_constraint :: " +ctx.getText());
        		tableconstraint.add(ctx.getText());
        	}
	
        	
        	
        	@Override public void exitCreate_table_stmt(SQLiteParser.Create_table_stmtContext ctx)
        	{ 
        	    /* create a pattern object for constrain matching of records */
    		    HeaderPattern pattern = new HeaderPattern();
    		  
    		    /* the pattern always starts with a header constraint */ 
    		    pattern.addHeaderConstraint(colnames.size()+1,colnames.size()*2);
    		 
        		
        		int cc = 0;
        		ListIterator<String> list = coltypes.listIterator();
        		while(list.hasNext())
        		{	
        			String coltype = list.next();
        			/*
        			 * columns without (known) type can hold any value - a constraint matching
        			 * everything would turn the table into a wildcard for carving
        			 */
        			if (untypedColumns.contains(cc)) {
        				cc++;
        				continue;
        			}
        		
	        		switch (coltype) {
					case "INT":
						if (constraints.containsKey(cc))
							pattern.add(new IntegerConstraint(true));
						else
							pattern.add(new IntegerConstraint(false));
						
						break;
	
					case "TEXT":
						pattern.addStringConstraint();
						break;
	
					case "BLOB":
						pattern.addBLOBConstraint();
						break;
	
					case "REAL":
						
						pattern.addFloatingConstraint();
						break;
	
					case "NUMERIC":
						pattern.addNumericConstraint();
						break;
					}
	        		cc++;
        		}
	        		
    	
        		tds = new TableDescriptor(tablename,stmt,sqltypes,coltypes,colnames,colconstraints,tableconstraint,pattern,isWithoutRowid(stmt));
        		info("PATTTERN: " + pattern);
        		
        	}
        	
        	
        },tree);
        
		return tds;
	}
	
	
	/**
	 * Find out if there is any type information (key word) in
	 * the substring 
	 * @param s the string, that should be parsed. 
	 * @return  the type (INT, TEXT, REAL etc.)
	 */
	private String getType(String s)
	{
		String type="";
		
		/* Attention: issue 3 */
        s = s.toUpperCase();
		
		info("Datentyp" + s);
		
		if (s.startsWith("TIMESTAMP"))
		{
			type="INT";
		}	
		else if (stringContainsItemFromList(s, texttypes))
		{
			type="TEXT";
		}
		else if (stringContainsItemFromList(s, inttypes))
		{
			type="INT";
		}
		else if (stringContainsItemFromList(s, blobtype))
		{
			type="BLOB";
		}
		else if (stringContainsItemFromList(s, realtype))
		{
			type="REAL";
		}
		else if (stringContainsItemFromList(s, numerictype))
		{
			type="NUMERIC";	
		}
		return type;
	}
	
	public static boolean stringContainsItemFromList(String inputStr, String[] items) {
		return Arrays.stream(items).parallel().anyMatch(inputStr::contains);
	}
	
	
}
