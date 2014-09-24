# Spark shuffle研究
版本：1.1.0

不管是hadoop中map/reduce还是spark中各种算子，shuffle过程都是其中核心过程，shuffle的设计是否高效，基本确定了整个计算过程是否高效。
设计难点在于shuffle过程涉及到大数据的IO操作（包括本地临时文件IO，以及网络IO），以及可能存在的cpu密集型排序计算操作。  

刚刚发布的spark1.1版本，spark针对大型数据引入一个新的shuffle实现，即“sort-based shuffle”
> This release introduces a new shuffle implementation optimized for very large scale shuffles. 
> This “sort-based shuffle” will be become the default in the next release, and is now available to users. 
> For jobs with large numbers of reducers, we recommend turning this on. 

本文针对shuffle相关的代码逻辑做一次串读，其中包括shuffle的原理，以及shuffle代码级别的实现。

## Pipe化

## Dependency与Stage的关系
