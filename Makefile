BUILD := build
CLASSES := $(BUILD)/classes
DEX := $(BUILD)/classes.dex
JAR := $(BUILD)/continuous-animations.jar
PLUGIN := dist/continuous_animations.plugin
DX ?= /usr/lib/android-sdk/build-tools/debian/dx

.PHONY: all clean dex plugin verify
all: plugin

$(CLASSES):
	mkdir -p $@
	javac --release 8 -d $@ $$(find src/compileStubs/java src/main/java -name '*.java' -print)

$(JAR): $(CLASSES)
	jar --create --file $@ -C $(CLASSES) app/soranerai/continuousanimations

$(DEX): $(JAR)
	$(DX) --dex --output=$@ $(JAR)

plugin: $(DEX) plugin/continuous_animations.plugin.template tools/package_plugin.py
	python3 tools/package_plugin.py --template plugin/continuous_animations.plugin.template --dex $(DEX) --output $(PLUGIN)

verify: plugin
	python3 -m py_compile $(PLUGIN)
	python3 -c "import ast,base64,pathlib; p=pathlib.Path('$(PLUGIN)'); t=ast.parse(p.read_text()); s=next(n.value.value for n in t.body if isinstance(n,ast.Assign) and any(isinstance(x,ast.Name) and x.id == '_DEX_B64' for x in n.targets)); assert base64.b64decode(s).startswith(b'dex\\n'); print('plugin verified')"

clean:
	rm -rf $(BUILD) dist
