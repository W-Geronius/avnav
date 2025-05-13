/*
 * add updates from the store to components
 * it adds 2 properties to the component:
 * storeKeys: either an array of keys or an object with the keys being the keys of state and the values the store keys
 * and the values being the keys for the state
 * updateFunction: optional - a function that will receive the values as fetched from the store
 * (possibly already translated if storeKeys was an object) and the list of keys and must return the new state
 */

import globalStore from "../util/globalstore.jsx";
import React, {Children, cloneElement, useCallback, useEffect, useRef, useState} from 'react';
import {KeyHelper} from "../util/keys";

const getStoreAndKeys=(props,options)=>{
    if (! options) options={};
    if (! props) props={};
    const usedStoreKeys=KeyHelper.removeNodeInfo({...options.storeKeys,...props.storeKeys});
    if (typeof(usedStoreKeys) !== 'object'){
        throw Error("invalid type of storeKeys: "+typeof(usedStoreKeys));
    }
    const store=options.store||globalStore;
    const usedUpdateFunction=options.updateFunction||props.updateFunction;
    return [store,usedStoreKeys,usedUpdateFunction];
}
const computeValues=(fw,storeKeys,data,usedUpdateFunction)=>{
    if (usedUpdateFunction) return {...fw,...usedUpdateFunction({...data},storeKeys)};
    return {...fw,...data};
}
const getStoreValues=(forwardData,store,storeKeys,updateFunction)=>{
    if (! store || ! storeKeys) return forwardData;
    let values=store.getMultiple(storeKeys);
    return computeValues(forwardData,storeKeys,values,updateFunction);
}

export const useStore=(props,opt_options)=>{
    if (! props) return;
    const {storeKeys,updateFunction,minTime,changeCallback,...forward}=props;
    if (! opt_options) opt_options= {};
    const [store,usedStoreKeys,usedUpdateFunction]=getStoreAndKeys(props,opt_options);
    if (! usedStoreKeys) {
        return props;
    }
    const lastUpdate=useRef(0);
    const timer=useRef(undefined);
    const [values,setValues]=useState(store.getMultiple(usedStoreKeys));
    const usedChangeCallback=changeCallback||opt_options.changeCallback;
    const callbackRef=useRef();
    useState(()=>{
        callbackRef.current=store.register(()=>{
            const usedMinTime=opt_options.minTime||minTime;
            if (usedMinTime){
                if (timer.current !== undefined){
                    window.clearTimeout(timer.current);
                    timer.current=undefined;
                }
                const now=(new Date()).getTime();
                const tdiff=now-(lastUpdate.current + usedMinTime);
                if (tdiff < 0){
                    timer.current=window.setTimeout(()=>doSetValues(store.getMultiple(usedStoreKeys)),-tdiff);
                    return;
                }
            }
            doSetValues(store.getMultiple(usedStoreKeys));
        },storeKeys)
    })
    const doSetValues=(data)=>{
        setValues(data);
        lastUpdate.current=(new Date()).getTime();
        if (usedChangeCallback) usedChangeCallback(computeValues(forward,usedStoreKeys,data,usedUpdateFunction));
    }
    useEffect(() => {
        return ()=>store.deregister(callbackRef.current);
    }, []);
    if (usedChangeCallback){
        useEffect(() => {
            usedChangeCallback(computeValues(forward,usedStoreKeys,values,usedUpdateFunction));
        }, []);
    }
    return computeValues(forward,usedStoreKeys,values,usedUpdateFunction);
}

export const DynamicFrame=(props)=>{
    const values=useStore(props);
    return <React.Fragment>
        {Children.map(props.children,(child)=>cloneElement(child,values))}
    </React.Fragment>
}
/**
 * compute the current values for an item with storeKeys
 * @param props
 * @param options
 * @returns {*|(*)|(*)}
 */
export const dynamicWrapper=(props,options)=>{
    if (! props) return;
    const [store,storeKeys,updateFunctions]=getStoreAndKeys(props,options);
    if (! storeKeys) return props;
    return getStoreValues(props,store,storeKeys,updateFunctions);
}

export default  function(Component,opt_options,opt_store){
    let store=opt_store||globalStore;
     return (props)=>{
        const currentValues=useStore(props,{...opt_options,store:store});
        return <Component {...currentValues}/>
    }
}
