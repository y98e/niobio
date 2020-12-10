# Niobio
> Niobio Cash swap proposal.

## Description
Unique code. Bitcoin (Satoshi White Paper) without scripting language, which allows advances in the implementation over Bitcoin Core, such as fusion transactions (join outputs, reducing memory consumption), UTXO snapshots (instant sync) and easy shortcuts (human friendly) for address. 

*A simpler bitcoin, a better bitcoin.*

## What is crypto? What is bitcoin (niobio)?

[Good guide here](https://learnmeabitcoin.com/)

Basically three softwares:

**daemon** P2p network exchanging data (blocks and transactions).

**miner** Mining (proof of work)

**wallet** (Private keys, signature, a way to spend money)

-------- *Daemon* --------

The main program. Make connections to the seeds nodes (servers) and put it self as a server too (p2p). It takes care of exchange messages on the network and maintain blockchain (persistent data) and the data related to each chain (UTXO). By taking care of the Blockchain, it responds to RPC queries, such as any transaction data, chain height, etc.

-------- *Miner* --------

Simple program that asks (RPC) the Daemon for the data of the best chain to try to create the next block. Basically an infinite loop looking for the number (NONCE) that will make the block hash valid (block hash must be below TARGET). When the daemon receives a new valid block, which modifies the best chain, it alerts the miner (RPC) in order to stop that mining and start mining the next block. 

-------- *Wallet* --------

A simple program that maintains a list of key pairs (public and private) that queries the daemon to know the balance (sum of unspent outputs) of each public key. Responsible for creating and signing the transactions, sending them to the daemon (and from there to the network). The program can become complex if it keep a list of the transactions created, since those who execute the transactions are the miners through the blocks, it will have to constantly query the daemon to know the status of your transactions and, as there can be exchange of the best chain, it need to be aware of that too.

## How is this implemented in Niobio?

**Java Main**
*public static void main(final String[] args)*

1- coin.daemon.Daemon

2- coin.miner.Miner

3- coin.wallet.Wallet


-------- *Daemon* --------

Java package **coin.daemon**

Files ** Daemon, Node and Blockchain ** 

Node.java = Responsible for sending and receiving data on the p2p network.

Blockchain.java = Responsible for maintaining the blockchain (blocks) and the relative data of each chain (UTXO).

Daemon.java = java Main program. Responsible for being the glue that links Node to Blockchain and also for responding to RPC queries.

-------- *Miner* --------

Java package **coin.miner**

Files ** Miner and Candidate ** 

Miner.java = java Main program. Responsible ask and receive *block template* through RPC queries from Daemon. **Block template** is the information needed to assemble the next block in the chain.

Candidate.java = Responsible for mining (looking for NONCE that leave the block hash below TARGET).

-------- *Wallet* --------

Java package **coin.wallet**

Files ** Wallet** 

Wallet.java = java Main program. Responsible for create key pairs (public and private) and sign (create) transactions.

## How To

*INFO: "Public Key" and "Address" are the same thing here.*

For the first time, do

```
git clone https://github.com/niobio-cash/niobio.git
cd niobio/
```

**1- Run Daemon**

```
docker build --build-arg RUN=coin.run.RunDaemon -t daemon .
docker run -it -p 10762:10762 -p 8080:8080 -v "${PWD}/data:/niobio/data" --rm daemon
```

**2- Run Wallet**

```
docker build --build-arg RUN=coin.run.RunWallet -t wallet .
docker run -it -p 8081:8081 -v "${PWD}/data:/niobio/data" --rm wallet
```

**2- Run Miner (check YOUR_ADDRESS)**

```
docker build --build-arg RUN=coin.run.RunMiner --build-arg PARAM=YOUR_ADDRESS -t miner .
docker run -it -p 8082:8082 -v "${PWD}/data:/niobio/data" --rm miner
```

## Create a new keypair (Public Key + Private Key)

```
curl --header "Content-Type: application/json" --request POST --data '{"method":"createKey"}' http://localhost:8081
```

Check path *niobio/data/keypair* to see if your new key is there. Filename is your address (public key)

```
ls -l niobio/data/keypair/
```

That's it! :-D

To stop, try

```
docker ps
docker stop CONTAINER_ID
```

## Run Block Explorer

```
docker-compose up
```

## API (RPC)

*INFO: "Public Key" and "Address" are the same thing here.*

*Daemon*

**getBalance** PUBLIC_KEY

```
curl --header "Content-Type: application/json" --request POST --data '{"method":"getBalance", "pubkey":"PUBLIC_KEY"}' http://localhost:8080
```

**getBlock** HASH

```
curl --header "Content-Type: application/json" --request POST --data '{"method":"getBlock", "hash":"HASH"}' http://localhost:8080
```

**getTransaction** HASH

```
curl --header "Content-Type: application/json" --request POST --data '{"method":"getTransaction", "hash":"HASH"}' http://localhost:8080
```

**getBestChain**

```
curl --header "Content-Type: application/json" --request POST --data '{"method":"getBestChain"}' http://localhost:8080
```

**getMempool**

```
curl --header "Content-Type: application/json" --request POST --data '{"method":"getMempool"}' http://localhost:8080
```

**circulatingSupply**

```
curl --header "Content-Type: application/json" --request POST --data '{"method":"circulatingSupply"}' http://localhost:8080
```

*Wallet*

**createKey**

```
curl --header "Content-Type: application/json" --request POST --data '{"method":"createKey"}' http://localhost:8081
```

**send** FROM_PUBLIC_KEY, TO_PUBLIC_KEY and AMOUNT

```
curl --header "Content-Type: application/json" --request POST --data '{"method":"send","from":"FROM_PUBLIC_KEY","to":"TO_PUBLIC_KEY","amount":AMOUNT}' http://localhost:8081
```

**getKeys**

```
curl --header "Content-Type: application/json" --request POST --data '{"method":"getKeys"}' http://localhost:8081
```

## Help

If you need my help, don't hesitate to ask me.

[My twitter](https://twitter.com/_oliberal)

Enjoy!