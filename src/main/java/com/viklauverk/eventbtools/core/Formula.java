/*
 Copyright (C) 2021 Viklauverk AB
 Author Fredrik Öhrström

 This program is free software: you can redistribute it and/or modify
 it under the terms of the GNU Affero General Public License as published by
 the Free Software Foundation, either version 3 of the License, or
 (at your option) any later version.

 This program is distributed in the hope that it will be useful,
 but WITHOUT ANY WARRANTY; without even the implied warranty of
 MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 GNU Affero General Public License for more details.

 You should have received a copy of the GNU Affero General Public License
 along with this program.  If not, see <http://www.gnu.org/licenses/>.
*/

package com.viklauverk.eventbtools.core;

import static com.viklauverk.eventbtools.core.Node.*;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.LinkedList;
import java.util.TreeMap;

import org.antlr.v4.runtime.CharStream;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.tree.ParseTree;

public
class Formula
{
    private static Log log = LogModule.lookup("formula");

    // This canvas is used to render formulas for caching and internal types.
    private static Canvas raw_unicode_canvas_;

    static
    {
        raw_unicode_canvas_ = new Canvas();
        raw_unicode_canvas_.setRenderTarget(RenderTarget.PLAIN);
        raw_unicode_canvas_.useRaw(true);
    }

    private Node node_;
    private int[] data_;         // Raw bytes storing: a symbol index or number int/long.
    private Formula[] children_; // The children nodes of the formula, if there are any.
    private String cache_;       // A cached unicode/utf8 string representation of this formula.

    @Override
    public String toString()
    {
        if (cache_ != null) return cache_;

        // This is safe to run twice, if there is a toString race initially.
        RenderFormulaUnicode gen = new RenderFormulaUnicode(raw_unicode_canvas_.copy());
        VisitFormula.walk(gen, this);
        String t = gen.cnvs().render();
        cache_ = t.intern(); // This guarantees a unique string pointer even in the case of a race.
        return cache_;
    }

    public String toString(Canvas c)
    {
        RenderFormula gen = null;
        switch (c.renderTarget())
        {
        case PLAIN:
            gen = new RenderFormulaUnicode(c);
            break;
        case TERMINAL:
            gen = new RenderFormulaUnicode(c);
            break;
        case TEX:
            gen = new RenderFormulaTeX(c);
            break;
        case HTMQ:
            gen = new RenderFormulaHtmq(c);
            break;
        default:
        assert (false) : "Unknown render target \""+c.renderTarget()+"\" when translating a formula into a string.";
        }
        VisitFormula.walk(gen, this);
        return gen.cnvs().render();
    }

    public Node node()
    {
        return node_;
    }

    public boolean is(Node n)
    {
        return node_ == n;
    }

    private int dataSize()
    {
        if (data_ == null) return 0;
        return data_.length;
    }

    public int intData()
    {
        assert (dataSize() >= 1) : "Trying to get int data from a formula that does not have such data!";
        return data_[0];
    }

    public boolean isSymbol()
    {
        return node_.isSymbol();
    }

    public String symbol()
    {
        assert isSymbol() : "internal error: requesting symbol of non-symbol: "+this;

        return toString();
    }

    public int numChildren()
    {
        if (children_ == null) return 0;
        return children_.length;
    }

    public Formula child(int c)
    {
        assert (c < numChildren()) : "Trying to get a child formula that does not exist!";
        return children_[c];
    }

    public Formula child()
    {
        assert (numChildren() == 1) : "Trying to get the child formula, but there is not exactly one child formula.";
        return child(0);
    }

    public List<Formula> children()
    {
        if (children_ != null)
        {
            return Collections.unmodifiableList(Arrays.asList(children_));
        }
        return new LinkedList<Formula>();
    }

    public Formula left()
    {
        assert (numChildren() == 2) : "Trying to invoke left on a formula that does not have exactly two children.";
        return child(0);
    }

    public Formula right()
    {
        assert(numChildren() == 2) : "Trying to invoke right on a formula that does not have exactly two children.";
        return child(1);
    }

    Formula(Node node)
    {
        node_ = node;
    }

    Formula(Node node, int v)
    {
        node_ = node;
        data_ = new int[1];
        data_[0] = v;
    }

    Formula(Node node, long v)
    {
        node_ = node;
        data_ = new int[2];
        data_[0] = (int) (v & 0xffffffff);
        data_[1] = (int) (v >>> 32);
    }

    Formula(Node node, Formula inner)
    {
        assert (node != null && inner != null) : "Internal error when creating formula, args must be non-null.";

        node_ = node;
        children_ = new Formula[1];
        children_[0] = inner;
    }

    Formula(Node node, Formula left, Formula right)
    {
        assert (node != null && left != null && right != null) : "Internal error when creating formula, args must be non-null.";

        node_ = node;
        children_ = new Formula[2];
        children_[0] = left;
        children_[1] = right;
    }

    Formula(Node node, Formula vars, Formula pred, Formula expr)
    {
        assert (node != null && vars != null && pred!= null && expr!= null) : "Internal error when creating formula, args must be non-null.";

        node_ = node;
        children_ = new Formula[3];
        children_[0] = vars;
        children_[1] = pred;
        children_[2] = expr;
    }

    Formula(Node node, List<Formula> inners)
    {
        assert (node != null && inners != null) : "Internal error when creating formula, args must be non-null.";

        node_ = node;
        children_ = new Formula[inners.size()];
        int i = 0;
        for (Formula f : inners)
        {
            children_[i] = f;
            assert (f != null) : "internal error: child formula must never be null!";
            i++;
        }
    }

    public static
    Formula fromString (String s, SymbolTable fc)
    {
        log.debug("parsing %s", s);
        Formula f = parse(s, fc);
        if (f == null)
        {
            System.out.print("Could not parse formula:\n    ");
            System.out.println(s);
            System.out.println("\nWhile using symbol table:");
            fc.print();
            System.exit(1);
        }
        return f;
    }

    public static
    Formula fromStringMightFail(String s, SymbolTable fc)
    {
        log.debug("parsing %s", s);
        Formula f = parse(s, fc);
        if (f == null)
        {
            System.out.print("Could not parse formula :\n    ");
            System.out.println(s);
            System.out.println("\nWhile using symbol table:");
            fc.print();
        }
        return f;
    }

    public String toStringWithTypes()
    {
        RenderFormulaUnicode gen = new RenderFormulaUnicode(raw_unicode_canvas_.copy());
        gen.addTypes();
        VisitFormula.walk(gen, this);
        return gen.cnvs().render();
    }

    public String toStringWithTypes(Canvas canvas)
    {
        RenderFormulaUnicode gen = new RenderFormulaUnicode(canvas);
        gen.addTypes();
        VisitFormula.walk(gen, this);
        return gen.cnvs().render();
    }

    public boolean isPredicate()
    {
        return node_.isPredicate();
    }

    public boolean isExpression()
    {
        return node_.isExpression();
    }

    public boolean isSet()
    {
        return node_.isSet();
    }

    public boolean isVariable()
    {
        return node_.isVariable();
    }

    public boolean isConstant()
    {
        return node_.isConstant();
    }

    public boolean isNumber()
    {
        return node_ == Node.NUMBER;
    }

    public boolean equals(Formula f)
    {
        if (this == f) return true;
        if (node() != f.node()) return false;
        if (node().isSymbol() && f.node().isSymbol())
        {
            return symbol().equals(f.symbol());
        }
        if (numChildren() != f.numChildren()) return false;
        int n = numChildren();
        for (int i=0; i<n; ++i)
        {
            boolean eq = child(i).equals(f.child(i));
            if (!eq) return false;
        }
        return true;
    }

    public Formula range()
    {
        assert (node().isRelation()) : "Trying to invoke range on something that is not a relation.";

        return right();
    }

    public Formula domain()
    {
        assert (node().isRelation()) : "Trying to invoke domain on something that is not a relation.";

        return left();
    }

    private static Formula parse(String line, SymbolTable fc)
    {
        log.trace("parsing "+line);
        CharStream lineStream = CharStreams.fromString(line);

        EvBFormulaLexer lexer = new EvBFormulaLexer(lineStream);
        lexer.symbol_table = fc;
        CommonTokenStream tokens = new CommonTokenStream(lexer);
        EvBFormulaParser parser = new EvBFormulaParser(tokens);
        parser.symbol_table = fc;
        //parser.setTrace(true);
        ParseTree tree = null;
        try
        {
            tree = parser.start();
        }
        catch (Exception e)
        {
            e.printStackTrace();
            System.exit(1);
        }
        if (parser.getNumberOfSyntaxErrors() > 0)
        {
            return null;
        }

        FormulaBuilder fbv = new FormulaBuilder(tokens);
        Formula result = fbv.visit(tree);

        return result;
    }


}
