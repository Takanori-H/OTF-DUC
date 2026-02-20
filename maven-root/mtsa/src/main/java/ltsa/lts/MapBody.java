package ltsa.lts;

import java.util.Hashtable;
import java.util.Vector;

/**
 * 遅延評価用のMap環境ボディ
 * 合成(compose)が呼び出されたタイミングで MappingEnvironmentGenerator を実行する
 */
public class MapBody extends CompositeBody {
    
    private MapDefinition mapDef;
    private Hashtable<String, RelationDefinition> relations;

    public MapBody(MapDefinition mapDef, Hashtable<String, RelationDefinition> relations) {
        this.mapDef = mapDef;
        this.relations = relations;
    }

    @Override
    void compose(CompositionExpression c, Vector machines, Hashtable<String, Value> locals) {
        // 1. Generatorの準備
        MappingEnvironmentGenerator generator = new MappingEnvironmentGenerator();
        
        // 2. 依存関係の解決 (Old/Newプロセスがまだコンパイルされていない場合にコンパイルする)
        ensureCompiled(mapDef.oldProcess.toString(), c);
        ensureCompiled(mapDef.newProcess.toString(), c);

        // 3. Map環境の生成
        // c.compiledProcesses には、すでにコンパイル済みのプロセスが入っている
        // relations は LTSCompiler から引き継いだものを使用
        CompactState mapEnv = generator.generate(mapDef, c.compiledProcesses, relations, c.output);

        if (mapEnv != null) {
            machines.add(mapEnv);
            // 生成結果をキャッシュする（再利用のため）
            c.compiledProcesses.put(mapEnv.name, mapEnv);
        } else {
            Diagnostics.fatal("Failed to generate Mapping Environment: " + mapDef.name);
        }
    }

    /**
     * 指定された名前のプロセスが compiledProcesses に存在しない場合、
     * c.processes (ProcessSpec) から探してコンパイルし、登録する。
     */
    private void ensureCompiled(String name, CompositionExpression c) {
        // 既にコンパイル済みなら何もしない
        if (c.compiledProcesses.containsKey(name)) {
            return;
        }

        // 定義済みプロセス(ProcessSpec)を探す
        ProcessSpec p = c.processes.get(name);
        if (p != null) {
            try {
                c.output.outln("INFO: Auto-compiling dependency for Map: " + name);
                StateMachine sm = new StateMachine(p, new Vector<>()); 
                CompactState cs = sm.makeCompactState();
                cs.name = name; // 名前を確定
                c.compiledProcesses.put(name, cs);
            } catch (Exception e) {
                Diagnostics.fatal("Error compiling dependency '" + name + "': " + e);
            }
        } else {
            // ProcessSpecにも見つからない場合
            // Generator側で詳細なエラーを出すか、ここで警告を出す
            c.output.outln("Warning: Process '" + name + "' not found in definition.");
        }
    }
}