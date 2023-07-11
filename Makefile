.PHONY: clean

all: clean target/scala-2.12/observedg.jar

clean:
	rm -rf target dist

target/scala-2.12/observedg.jar:
	sbt assembly
