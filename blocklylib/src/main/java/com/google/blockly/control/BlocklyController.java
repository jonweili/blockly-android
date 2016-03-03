/*
 * Copyright 2016 Google Inc. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.blockly.control;

import android.app.Activity;
import android.content.Context;
import android.content.res.AssetManager;
import android.os.Bundle;
import android.support.annotation.Nullable;
import android.support.v4.app.FragmentManager;
import android.support.v4.widget.DrawerLayout;
import android.util.Log;
import android.view.MotionEvent;

import com.google.blockly.ToolboxFragment;
import com.google.blockly.TrashFragment;
import com.google.blockly.WorkspaceFragment;
import com.google.blockly.model.Block;
import com.google.blockly.model.BlockFactory;
import com.google.blockly.model.BlocklyParserException;
import com.google.blockly.model.BlocklySerializerException;
import com.google.blockly.model.Connection;
import com.google.blockly.model.Input;
import com.google.blockly.model.Workspace;
import com.google.blockly.ui.BlockGroup;
import com.google.blockly.ui.BlockTouchHandler;
import com.google.blockly.ui.BlockView;
import com.google.blockly.ui.InputView;
import com.google.blockly.ui.ViewPoint;
import com.google.blockly.ui.WorkspaceHelper;
import com.google.blockly.ui.WorkspaceView;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;


/**
 * Controller to coordinate the state among all the major Blockly components: Workspace, Toolbar,
 * Trash, models, and views.
 */
public class BlocklyController {
    private static final String TAG = "BlocklyController";

    private static final String SNAPSHOT_BUNDLE_KEY = "com.google.blockly.snapshot";
    private static final String SERIALIZED_WORKSPACE_KEY = "SERIALIZED_WORKSPACE";

    private final Context mContext;
    private final FragmentManager mFragmentManager;
    private final BlockFactory mBlockFactory;
    private final WorkspaceHelper mHelper;

    private final Workspace mWorkspace;
    private final ViewPoint mTempViewPoint = new ViewPoint();

    private WorkspaceView mWorkspaceView;
    private WorkspaceFragment mWorkspaceFragment = null;
    private TrashFragment mTrashFragment = null;
    private ToolboxFragment mToolboxFragment = null;
    private Dragger mDragger;

    private boolean mCanCloseToolbox;

    /** Pass block touch interaction through the Dragger. */
    private BlockTouchHandler mTouchHandler = new BlockTouchHandler() {
        @Override
        public boolean onTouchBlock(BlockView blockView, MotionEvent motionEvent) {
            return mDragger.onTouchBlock(blockView, motionEvent);
        }

        @Override
        public boolean onInterceptTouchEvent(BlockView blockView, MotionEvent motionEvent) {
            return mDragger.onInterceptTouchEvent(blockView, motionEvent);
        }
    };

    /**
     * Creates a new Controller with Workspace and WorkspaceHelper.
     *
     * @param context Android context, such as an Activity.
     * @param blockFactory Factory used to create new Blocks.
     * @param fragmentManager Support fragment manager, if controlling the Blockly fragment and view
     * classes.
     * @param style Workspace view style id, or 0.
     */
    private BlocklyController(Context context, BlockFactory blockFactory,
            @Nullable FragmentManager fragmentManager, int style) {

        if (context == null) {
            throw new IllegalArgumentException("context may not be null.");
        }
        if (blockFactory == null) {
            throw new IllegalArgumentException("blockFactory may not be null.");
        }
        mContext = context;
        mFragmentManager = fragmentManager;
        mBlockFactory = blockFactory;
        mWorkspace = new Workspace(mContext, this, mBlockFactory);
        mHelper = new WorkspaceHelper(mContext, style);

        // TODO(#81): Check if variables are enabled/disabled
        mHelper.setVariableNameManager(mWorkspace.getVariableNameManager());

        mDragger = new Dragger(this);
    }

    /**
     * Connects a WorkspaceFragment to this controller.
     *
     * @param workspaceFragment
     */
    public void setWorkspaceFragment(@Nullable WorkspaceFragment workspaceFragment) {
        if (workspaceFragment != null && mFragmentManager == null) {
            throw new IllegalStateException("Cannot set fragments without a FragmentManager.");
        }

        if (workspaceFragment == mWorkspaceFragment) {
            return;  // No-op
        }
        if (mWorkspaceFragment != null) {
            mWorkspaceFragment.setController(null);
        }
        mWorkspaceFragment = workspaceFragment;
        if (mWorkspaceFragment != null) {
            mWorkspaceFragment.setController(this);
        }
    }

    /**
     * Connects a {@link ToolboxFragment} to this controller, so the user can drag new blocks into
     * the attached {@link WorkspaceFragment}.
     *
     * @param toolboxFragment The toolbox to connect to.
     */
    public void setToolboxFragment(@Nullable ToolboxFragment toolboxFragment) {
        if (toolboxFragment == mToolboxFragment) {
            return;
        }

        if (mToolboxFragment != null) {
            // Reset old fragment.
            mToolboxFragment.setController(null);
        }

        mToolboxFragment = toolboxFragment;

        if (mToolboxFragment != null) {
            mToolboxFragment.setController(this);
            updateToolbox();
        }
    }

    /**
     * @return The currently attached {@link ToolboxFragment}.
     */
    public ToolboxFragment getToolboxFragment() {
        return mToolboxFragment;
    }

    /**
     * Connects a {@link TrashFragment} to this controller.
     *
     * @param trashFragment
     */
    public void setTrashFragment(@Nullable TrashFragment trashFragment) {
        if (trashFragment != null && mFragmentManager == null) {
            throw new IllegalStateException("Cannot set fragments without a FragmentManager.");
        }

        if (trashFragment == mTrashFragment) {
            return;  // No-op
        }
        if (mTrashFragment != null) {
            // Reset old fragment.
            mTrashFragment.setController(null);
        }
        mTrashFragment = trashFragment;
        if (mTrashFragment != null) {
            mTrashFragment.setController(this);
            mTrashFragment.setContents(mWorkspace.getTrashContents());
        }
    }

    /**
     * @return The currently attached {@link TrashFragment}.
     */
    public TrashFragment getTrashFragment() {
        return mTrashFragment;
    }

    /**
     * Loads the toolbox contents from a JSON resource file.
     *
     * @param toolboxJsonResId The resource id of JSON file (should be a raw resource file).
     */
    public void loadToolboxContents(int toolboxJsonResId) {
        mWorkspace.loadToolboxContents(toolboxJsonResId);
        updateToolbox();
    }

    /**
     * Loads the toolbox contents from a JSON string.
     *
     * @param toolboxJsonString The JSON source of the set of blocks or block groups to show in the
     *     toolbox.
     */
    public void loadToolboxContents(String toolboxJsonString) {
        mWorkspace.loadToolboxContents(toolboxJsonString);
        updateToolbox();
    }

    /**
     * Loads the toolbox contents from a JSON input stream.
     *
     * @param toolboxJsonStream A stream of the JSON source of the set of blocks or block groups to
     *    show in the toolbox.
     */
    public void loadToolboxContents(InputStream toolboxJsonStream) {
        mWorkspace.loadToolboxContents(toolboxJsonStream);
        updateToolbox();
    }

    /**
     * Reads the workspace in from a XML stream. This will clear the workspace and replace it with
     * the contents of the xml.
     *
     * @param workspaceXmlString The XML source string to read from.
     * @throws BlocklyParserException if there was a parse failure.
     */
    public void loadWorkspaceContents(String workspaceXmlString) throws BlocklyParserException {
        mWorkspace.loadWorkspaceContents(workspaceXmlString);
        initBlockViews();
    }

    /**
     * Reads the workspace in from a XML stream. This will clear the workspace and replace it with
     * the contents of the xml.
     *
     * @param workspaceXmlStream The input stream to read from.
     * @throws BlocklyParserException if there was a parse failure.
     */
    public void loadWorkspaceContents(InputStream workspaceXmlStream)
            throws BlocklyParserException {
        mWorkspace.loadWorkspaceContents(workspaceXmlStream);
        initBlockViews();
    }

    /**
     * Saves a snapshot of current workspace contents to a temporary cache file, and saves the
     * filename to the instance state bundle.
     * @param mSavedInstanceState
     * @return
     */
    public boolean onSaveSnapshot(Bundle mSavedInstanceState) {
        Bundle blocklyState = new Bundle();

        // First attempt to save the workspace to a file.
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            mWorkspace.serializeToXml(out);
            blocklyState.putByteArray(SERIALIZED_WORKSPACE_KEY, out.toByteArray());
        } catch (BlocklySerializerException e) {
            Log.w(TAG, e);
            return false;
        } finally {
            try {
                out.close();
            } catch (IOException e) {
                // Ignore.
            }
        }

        // TODO(#58): Save the rest of the state.

        // Success!
        mSavedInstanceState.putBundle(SNAPSHOT_BUNDLE_KEY, blocklyState);
        return true;
    }

    /**
     * Loads a Workspace state from an Android {@link Bundle}, previous saved in
     * {@link #onSaveSnapshot(Bundle)}.
     *
     * @param savedInstanceState The activity state Bundle passed into {@link Activity#onCreate} or
     *     {@link Activity#onRestoreInstanceState}.
     * @return True if a Blockly state was found and successfully loaded into the Controller.
     *     Otherwise, false.
     */
    public boolean onRestoreSnapshot(@Nullable Bundle savedInstanceState) {
        Bundle blocklyState = (savedInstanceState == null) ? null :
                savedInstanceState.getBundle(SNAPSHOT_BUNDLE_KEY);
        if (blocklyState != null) {
            byte[] bytes = blocklyState.getByteArray(SERIALIZED_WORKSPACE_KEY);
            if (bytes == null) {
                // Ignore all other workspace variables.
                return false;
            }
            ByteArrayInputStream in = new ByteArrayInputStream(bytes);
            try {
                mWorkspace.loadWorkspaceContents(in);
            } catch(BlocklyParserException e) {
                // Ignore all other workspace variables.
                Log.w(TAG, "Unable to restore Blockly state.", e);
                return false;
            } finally {
                try {
                    in.close();
                } catch (IOException e) {
                    // Ignore.
                }
            }

            // TODO(#58): Restore the rest of the state.

            return true;
        }
        return false;
    }

    public Context getContext() {
        return mContext;
    }

    public Workspace getWorkspace() {
        return mWorkspace;
    }

    public BlockFactory getBlockFactory() {
        return mBlockFactory;
    }

    public WorkspaceHelper getWorkspaceHelper() {
        return mHelper;
    }

    /**
     * Adds the provided block to the list of root blocks.  If the controller has an initialized
     * {@link WorkspaceView}, it will also create corresponding views.
     *
     * @param block The {@link Block} to add to the workspace.
     */
    public void addRootBlock(Block block) {
        if (block.getParentBlock() != null) {
            throw new IllegalArgumentException("New root block must not be connected.");
        }
        addRootBlock(block, mHelper.getParentBlockGroup(block), true);
    }

    /**
     * Takes a block, and adds it to the root blocks, disconnecting previous or output connections,
     * if previously connected.  No action if the block was already a root block.
     *
     * @param block {@link Block} to extract as a root block in the workspace.
     */
    public void extractBlockAsRoot(Block block) {
        Block rootBlock = block.getRootBlock();

        if (block == rootBlock) {
            return;
        }
        boolean isPartOfWorkspace = mWorkspace.isRootBlock(rootBlock);

        BlockView bv = block.getView();
        BlockGroup bg = (bv == null) ? null : (BlockGroup) bv.getParent();
        BlockGroup rootBlockGroup = (mWorkspaceView == null) ? null
                : mHelper.getRootBlockGroup(block);

        // Child block
        if (block.getPreviousConnection() != null
                && block.getPreviousConnection().isConnected()) {
            Input in = block.getPreviousConnection().getTargetConnection().getInput();
            if (in == null) {
                if (bg != null) {
                    // Next block
                    bg = bg.extractBlocksAsNewGroup(block);
                }
            } else {
                // Statement input
                // Disconnect view.
                InputView inView = in.getView();
                if (inView != null) {
                    inView.unsetChildView();
                }
            }
            block.getPreviousConnection().disconnect();
        } else if (block.getOutputConnection() != null
                && block.getOutputConnection().isConnected()) {
            // Value input
            Input in = block.getOutputConnection().getTargetConnection().getInput();
            block.getOutputConnection().disconnect();

            // Disconnect view.
            InputView inView = in.getView();
            if (inView != null) {
                inView.unsetChildView();
            }
        }

        if (rootBlockGroup != null) {
            rootBlockGroup.requestLayout();
        }
        if (isPartOfWorkspace) {
            // Only add back to the workspace if the original tree is part of the workspace model.
            addRootBlock(block, bg, false);
        }
    }

    /**
     * Set up the {@link WorkspaceView} with this workspace's model. This method will perform the
     * following steps: <ul> <li>Set the block touch handler for the view.</li> <li>Configure the
     * dragger for the view.</li> <li>Recursively initialize views for all the blocks in the model
     * and add them to the view.</li> </ul>
     *
     * @param wv The root workspace view to add to.
     */
    public void initWorkspaceView(final WorkspaceView wv) {
        mWorkspaceView = wv;
        mWorkspaceView.setController(this);

        mHelper.setWorkspaceView(wv);
        mDragger.setWorkspaceView(mWorkspaceView);
        mWorkspaceView.setDragger(mDragger);
        initBlockViews();
    }

    /**
     * Recursively initialize views for all the blocks in the model and add them to the view.
     */
    public void initBlockViews() {
        if (mWorkspaceView != null) {
            List<Block> rootBlocks = mWorkspace.getRootBlocks();
            ConnectionManager connManager = mWorkspace.getConnectionManager();
            for (int i = 0; i < rootBlocks.size(); i++) {
                BlockGroup bg = new BlockGroup(mContext, mHelper);
                mHelper.buildBlockViewTree(rootBlocks.get(i), bg, connManager,
                        mTouchHandler);
                mWorkspaceView.addView(bg);
            }
        }
    }

    /**
     * Create a new variable. If a variable with the same name already exists the name will be
     * modified to be unique.
     *
     * @param variable The desired name of the variable to create.
     * @return The actual variable name that was created.
     */
    public String addVariable(String variable) {
        return mWorkspace.getVariableNameManager().generateUniqueName(variable, true);
    }

    /**
     * Returns true if the specified variable is being used in a workspace.
     *
     * @param variable The variable the check.
     * @return True if the variable exists in a workspace, false otherwise.
     */
    public boolean isVariableInUse(String variable) {
        return mWorkspace.getVariableRefCount(variable) > 0;
    }

    /**
     * Remove a variable from the workspace. A variable may only be removed if it is not being used
     * in the workspace. {@link #isVariableInUse(String)} should be called to check if a variable
     * may be removed.
     *
     * @param variable The variable to remove.
     * @return True if the variable existed and was removed, false otherwise.
     * @throws IllegalStateException If there are still instances of that variable in the workspace.
     */
    public boolean removeVariable(String variable) {
        if (isVariableInUse(variable)) {
            throw new IllegalStateException(
                    "Cannot remove a variable that exists in the workspace.");
        }
        return mWorkspace.getVariableNameManager().remove(variable);
    }

    /**
     * Takes in a block model, creates corresponding views and adds it to the workspace.  Also
     * starts a drag of that block group.
     *
     * @param block The root block to be added to the workspace.
     * @param event The {@link MotionEvent} that caused the block to be added to the workspace. This
     * is used to find the correct position to start the drag event.
     */
    public void addBlockFromToolbox(Block block, MotionEvent event) {
        addRootBlock(block, null, true);
        // let the workspace view know that this is the block we want to drag
        mDragger.setTouchedBlock(block.getView(), event);
        // Adjust the event's coordinates from the {@link BlockView}'s coordinate system to
        // {@link WorkspaceView} coordinates.
        mHelper.workspaceToVirtualViewCoordinates(block.getPosition(), mTempViewPoint);
        mDragger.setDragStartPos((int) event.getX(), (int) event.getY(), mTempViewPoint.x,
                mTempViewPoint.y);
        mDragger.startDragging();
    }

    /**
     * Connects a block to a specific connection of another block.  The block must not have a
     * connected previous or output; usually a root block. If another block is in the way
     * of making the connection (occupies the required workspace location), that block will be
     * bumped out of the way.
     *
     * @param block The {@link Block} that is the root of the group of blocks being connected.
     * @param blockConnection The {@link Connection} of the block being moved to connect.
     * @param otherConnection The target {@link Connection} to connect to.
     */
    public void connect(Block block, Connection blockConnection, Connection otherConnection) {
        if (block.getPreviousBlock() != null) {
            throw new IllegalArgumentException("Connecting block must not be connected.");
        }
        Connection output = block.getOutputConnection();
        if (output != null && output.isConnected()) {
            throw new IllegalArgumentException("Connecting block must not be connected.");
        }

        switch (blockConnection.getType()) {
            case Connection.CONNECTION_TYPE_OUTPUT:
                removeRootBlock(block, false);
                connectAsInput(otherConnection, blockConnection);
                break;
            case Connection.CONNECTION_TYPE_PREVIOUS:
                removeRootBlock(block, false);
                if (otherConnection.isStatementInput()) {
                    connectToStatement(otherConnection, blockConnection.getBlock());
                } else {
                    connectAfter(otherConnection.getBlock(), blockConnection.getBlock());
                }
                break;
            case Connection.CONNECTION_TYPE_NEXT:
                if (!otherConnection.isConnected()) {
                    removeRootBlock(otherConnection.getBlock(), false);
                }
                if (blockConnection.isStatementInput()) {
                    connectToStatement(blockConnection, otherConnection.getBlock());
                } else {
                    connectAfter(blockConnection.getBlock(), otherConnection.getBlock());
                }
                break;
            case Connection.CONNECTION_TYPE_INPUT:
                if (!otherConnection.isConnected()) {
                    removeRootBlock(otherConnection.getBlock(), false);
                }
                connectAsInput(blockConnection, otherConnection);
                break;
            default:
                break;
        }
    }

    public void bumpBlock(Connection staticConnection, Connection impingingConnection) {
        Block rootBlock = impingingConnection.getBlock().getRootBlock();
        BlockGroup impingingBlockGroup = mHelper.getRootBlockGroup(rootBlock);

        int maxSnapDistance = mHelper.getMaxSnapDistance();
        int dx = (staticConnection.getPosition().x + maxSnapDistance)
                - impingingConnection.getPosition().x;
        int dy = (staticConnection.getPosition().y + maxSnapDistance)
                - impingingConnection.getPosition().y;
        rootBlock.setPosition(rootBlock.getPosition().x + dx, rootBlock.getPosition().y + dy);

        if (mWorkspaceView != null && impingingBlockGroup != null) {
            // Update UI
            impingingBlockGroup.bringToFront();
            impingingBlockGroup.updateAllConnectorLocations();
            mWorkspaceView.requestLayout();
        }
    }

    /**
     * Removes the given block from its parent, removes the block from the model, and then unlinks
     * all views.  All descendant of this block remain attached, and are thus also removed from the
     * workspace.
     *
     * @param block The {@link Block} to look up and remove.
     */
    public void removeBlockTree(Block block) {
        extractBlockAsRoot(block);
        removeRootBlock(block, true);
        unlinkViews(block);
    }

    /**
     * Remove a block and its descendants from the workspace and put it in the trash.  If the block
     * was not a root block of the workspace, do nothing and returns false.
     *
     * @param block The block to remove, possibly with descendants attached.
     * @return True if the block was removed, false otherwise.
     */
    // TODO(#56) Make this handle any block, not just root blocks.
    public boolean trashRootBlock(Block block) {
        boolean rootFoundAndRemoved = removeRootBlock(block, true);
        if (rootFoundAndRemoved) {
            mWorkspace.addBlockToTrash(block);
            unlinkViews(block);  // TODO(#77): Remove once TrashFragment can reuse views.

            mTrashFragment.onBlockTrashed(block);
        }

        return rootFoundAndRemoved;
    }

    /**
     * Recursively unlinks models from the views, and disconnects the view tree including clearing
     * the parent {@link BlockGroup}.
     *
     * @param block The root block of the tree to unlink.
     */
    public void unlinkViews(Block block) {
        BlockView view = block.getView();
        if (view == null) {
            return;  // No view to unlink.
        }

        // Verify the block has no parent.  Only unlink complete block sequences.
        if (block.getParentBlock() != null) {
            throw new IllegalArgumentException(
                    "Expected unconnected/root block; only allowed to unlink complete block trees");
        }

        BlockGroup parentGroup = mHelper.getParentBlockGroup(block);
        if (parentGroup != null) {
            if (parentGroup.getChildAt(0) != block.getView()) {
                // If it doesn't have a parent, this Block view should have been first.
                throw new IllegalStateException("BlockGroup does not match model");
            }
            parentGroup.unlinkModelAndSubViews();
        } else {
            if (view != null) {
                view.unlinkModelAndSubViews();
            }
        }
    }

    /**
     * Clears the workspace of all blocks and the respective views from the {@link WorkspaceView},
     * if connected.
     */
    public void resetWorkspace() {
        // Unlink the Views before wiping out the model's root list.
        ArrayList<Block> rootBlocks = mWorkspace.getRootBlocks();
        for (int i = 0; i < rootBlocks.size(); ++i) {
            unlinkViews(rootBlocks.get(i));
        }

        mWorkspace.resetWorkspace();
        if (mWorkspaceView != null) {
            mWorkspaceView.removeAllViews();
            initBlockViews();
        }
    }

    /**
     * Adds the provided block as a root block.  If a {@link WorkspaceView} is attached, it will
     * also update the view, creating a new {@link BlockGroup} if not provided.
     * <p/>
     * If {@code isNewBlock} is {@code true}, the system will collect stats about the connections,
     * functions and variables. This should only be {@code  false} if re-adding a moodel previously
     * removed via {@link #removeRootBlock(Block, boolean)} where {@code cleanupStats} was also
     * {@code false}.
     *
     * @param block The {@link Block} to add to the workspace.
     * @param bg The {@link BlockGroup} with block as the first {@link BlockView}.
     * @param isNewBlock Whether the block is new to the {@link Workspace} and the workspace should
     *                   collect stats for this tree.
     */
    private void addRootBlock(Block block, @Nullable BlockGroup bg, boolean isNewBlock) {
        mWorkspace.addRootBlock(block, isNewBlock);
        if (mWorkspaceView != null) {
            if (bg == null) {
                bg = mHelper.buildBlockGroupTree(block, mWorkspace.getConnectionManager(),
                        mTouchHandler);
            } else {
                bg.setTouchHandler(mTouchHandler);
            }
            mWorkspaceView.addView(bg);
        }
    }

    /**
     * Removes the given block from the {@link Workspace} and removes its view from the
     * {@link WorkspaceView}.  If the block is not a root block of the workspace, the method does
     * nothing and returns false.
     * <p/>
     * If {@code cleanupStats} is {@code false}, the system will retain stats about the available
     * connections, function definition, function and variable references. This should only be used
     * if the block will be immediately re-added to the model view {@link #addRootBlock} with
     * {@code isNewBlock} also {@code false}.
     *
     * @param block The {@link Block} to look up and remove.
     * @param cleanupStats Removes connection info and other stats.
     */
    private boolean removeRootBlock(Block block, boolean cleanupStats) {
        boolean rootFoundAndRemoved = mWorkspace.removeRootBlock(block, cleanupStats);
        if (rootFoundAndRemoved) {
            BlockView bv = block.getView();
            if (bv != null) {
                BlockGroup group = bv.getParentBlockGroup();
                if (group != null) {
                    // Update UI
                    mWorkspaceView.removeView(group);
                }
            }
        }
        return rootFoundAndRemoved;
    }

    /**
     * Connect a block to a statement input of another block and update views as necessary.  If the
     * statement input already is connected to another block, splice the inferior block between
     * them.
     *
     * @param parentStatementConnection The {@link Connection} on the superior block to be connected
     * to.  Must be on a statement input.
     * @param toConnect The {@link Block} to connect to the statement input.
     */
    private void connectToStatement(Connection parentStatementConnection, Block toConnect) {

        // If there was already a block connected there.
        if (parentStatementConnection.isConnected()) {
            Block remainderBlock = parentStatementConnection.getTargetBlock();
            parentStatementConnection.disconnect();
            InputView parentInputView = parentStatementConnection.getInputView();
            if (parentInputView != null) {
                parentInputView.unsetChildView();
            }

            // Try to reconnect the remainder to the end of the new sequence.
            Block lastBlock = toConnect.getLastBlockInSequence();
            if (lastBlock.getNextConnection() != null) {
                connectAfter(lastBlock, remainderBlock);
            } else {
                // Nothing to connect to.  Bump and add to root.
                addRootBlock(remainderBlock, mHelper.getParentBlockGroup(remainderBlock), false);
                bumpBlock(parentStatementConnection, remainderBlock.getPreviousConnection());
            }
        }
        connectAsInput(parentStatementConnection, toConnect.getPreviousConnection());
    }

    /**
     * Connect a block after another block in the same block group.  Updates views as necessary.  If
     * the superior block already has a "next" block, splices the inferior block between the
     * superior block and its "next" block.
     * <p/>
     * Assumes that the inferior's previous connection is disconnected. Assumes that inferior's
     * blockGroup doesn't currently live at the root level.
     *
     * @param superior The {@link Block} after which the inferior block is connecting.
     * @param inferior The {@link Block} to be connected as the superior block's "next" block.
     */
    private void connectAfter(Block superior, Block inferior) {
        // Get the relevant BlockGroups.  Either may be null if view is not initialized.
        BlockGroup superiorBlockGroup = mHelper.getParentBlockGroup(superior);
        BlockGroup inferiorBlockGroup = mHelper.getParentBlockGroup(inferior);

        // To splice between two blocks, just need another call to connectAfter.
        if (superior.getNextConnection().isConnected()) {
            Block remainderBlock = superior.getNextBlock();
            BlockGroup remainderGroup = (superiorBlockGroup == null) ? null :
                    superiorBlockGroup.extractBlocksAsNewGroup(remainderBlock);
            superior.getNextConnection().disconnect();

            // Try to reconnect the remainder to the end of the new sequence.
            Block lastBlock = inferior.getLastBlockInSequence();
            if (lastBlock.getNextConnection() != null) {
                connectAfter(lastBlock, inferiorBlockGroup, remainderBlock, remainderGroup);
            } else {
                // Nothing to connect to.  Bump and add to root.
                addRootBlock(remainderBlock, remainderGroup, false);
                bumpBlock(inferior.getPreviousConnection(), remainderBlock.getPreviousConnection());
            }
        }

        connectAfter(superior, superiorBlockGroup, inferior, inferiorBlockGroup);
    }

    /**
     * Connects two blocks together in a previous-next relationship and merges the {@link
     * BlockGroup} of the inferior block into the {@link BlockGroup} of the superior block.
     *
     * @param superior The {@link Block} that the inferior block is moving to attach to.
     * @param superiorBlockGroup The {@link BlockGroup} belonging to the superior block.
     * @param inferior The {@link Block} that will follow immediately after the superior block.
     * @param inferiorBlockGroup The {@link BlockGroup} belonging to the inferior block.
     */
    private void connectAfter(Block superior, BlockGroup superiorBlockGroup, Block inferior,
            BlockGroup inferiorBlockGroup) {
        // The superior's next connection and the inferior's previous connections must already be
        // disconnected.
        superior.getNextConnection().connect(inferior.getPreviousConnection());
        if (superiorBlockGroup != null) {
            if (inferiorBlockGroup == null) {
                inferiorBlockGroup = mHelper.buildBlockGroupTree(
                        inferior, mWorkspace.getConnectionManager(), mTouchHandler);
            }
            superiorBlockGroup.moveBlocksFrom(inferiorBlockGroup, inferior);
        }
    }

    /**
     * Connect a block or block group to an input on another block and update views as necessary. If
     * the input was already connected, splice the child block or group in.
     *
     * @param parentConn The {@link Connection} on the superior block to connect to.  Must be an
     *                   input.
     * @param childConn The {@link Connection} on the inferior block.  Must be an output or previous
     *                  connection.
     */
    private void connectAsInput(Connection parentConn, Connection childConn) {
        InputView parentInputView = parentConn.getInputView();
        Block child = childConn.getBlock();
        BlockGroup childBlockGroup = mHelper.getParentBlockGroup(child);

        Connection previousTargetConnection = null;
        if (parentConn.isConnected()) {
            previousTargetConnection = parentConn.getTargetConnection();
            parentConn.disconnect();
            if (parentInputView != null) {
                parentInputView.unsetChildView();
            }
        }
        parentConn.connect(childConn);
        if (previousTargetConnection != null) {
            Block previousTargetBlock = previousTargetConnection.getBlock();

            // Traverse the tree to ensure it doesn't branch. We only reconnect if there's a single
            // place it could be rebased to.
            Connection lastInputConnection = child.getLastUnconnectedInputConnection();
            if (lastInputConnection != null) {
                connectAsInput(lastInputConnection, previousTargetConnection);
            } else {
                // Bump and add back to root.
                BlockGroup previousTargetGroup = mHelper.getParentBlockGroup(previousTargetBlock);
                addRootBlock(previousTargetBlock, previousTargetGroup, false);
                bumpBlock(parentConn, previousTargetConnection);
            }
        }

        if (mWorkspaceView != null && parentInputView != null) {
            if (childBlockGroup == null) {
                childBlockGroup = mHelper.buildBlockGroupTree(
                        child, mWorkspace.getConnectionManager(), mTouchHandler);
            }
            parentInputView.setChildView(childBlockGroup);
        }
    }

    /**
     * Populates the toolbox fragments with the current toolbox contents.
     */
    private void updateToolbox() {
        if (mToolboxFragment != null) {
            mToolboxFragment.setContents(mWorkspace.getToolboxContents());
        }
    }

    /**
     * Builder for configuring a new controller and workspace.
     */
    public static class Builder {
        private Context mContext;
        private WorkspaceFragment mWorkspaceFragment;
        private ToolboxFragment mToolboxFragment;
        private DrawerLayout mToolboxDrawer;
        private TrashFragment mTrashFragment;
        private FragmentManager mFragmentManager;
        private AssetManager mAssetManager;
        private int mStyle;

        // TODO: Should these be part of the style?
        private int mToolboxResId;
        private String mToolboxAssetId;
        private String mToolboxXml;
        private ArrayList<Integer> mBlockDefResources = new ArrayList<>();
        private ArrayList<String> mBlockDefAssets = new ArrayList<>();
        private ArrayList<Block> mBlockDefs = new ArrayList<>();


        public Builder(Context context) {
            mContext = context;
        }

        public Builder setWorkspaceFragment(WorkspaceFragment workspace) {
            mWorkspaceFragment = workspace;
            return this;
        }

        public Builder setToolboxFragment(ToolboxFragment toolbox, DrawerLayout toolboxDrawer) {
            mToolboxFragment = toolbox;
            mToolboxDrawer = toolboxDrawer;
            return this;
        }

        public Builder setTrashFragment(TrashFragment trash) {
            mTrashFragment = trash;
            return this;
        }

        public Builder setAssetManager(AssetManager manager) {
            mAssetManager = manager;
            return this;
        }

        /**
         * A {@link FragmentManager} is used to show and hide the Toolbox or Trash. It is required
         * if you have set a {@link TrashFragment} or a {@link ToolboxFragment} that is not always
         * visible.
         *
         * @param fragmentManager The support manager to use for showing and hiding fragments.
         * @return this
         */
        public Builder setFragmentManager(FragmentManager fragmentManager) {
            mFragmentManager = fragmentManager;
            return this;
        }

        /**
         * Set the resource id for the style to use when rendering blocks. The style must inherit
         * from {@link com.google.blockly.R.style#BlocklyTheme}.
         *
         * @param styleResId The resource id for the style to use.
         * @return this
         */
        public Builder setBlocklyStyle(int styleResId) {
            mStyle = styleResId;
            return this;
        }

        /**
         * Add a set of block definitions to load from a resource file. These will be added to the
         * set of all known blocks, but will not appear in the user's toolbox unless they are also
         * defined in the toolbox configuration via {@link #setToolboxConfigurationResId(int)}.
         * <p/>
         * The resource must be a json file in the raw directory. If the file contains blocks that
         * were previously defined they will be overridden.
         * <p/>
         * A duplicate block is any block with the same {@link Block#getName() name}.
         *
         * @param blockDefinitionsResId The resource to load blocks from.
         * @return this
         */
        public Builder addBlockDefinitions(int blockDefinitionsResId) {
            mBlockDefResources.add(blockDefinitionsResId);
            return this;
        }

        /**
         * Add a set of block definitions to load from a JSON asset file. These will be added to the
         * set of all known blocks, but will not appear in the user's toolbox unless they are also
         * defined in the toolbox configuration via {@link #setToolboxConfigurationResId(int)}.
         * <p/>
         * The asset name must be a path to a file in the assets directory. If the file contains
         * blocks that were previously defined, they will be overridden. A duplicate block is any
         * block with the same {@link Block#getName() name}.
         *
         * @param assetName the path of the asset to load from.
         * @return this
         */
        public Builder addBlockDefinitionsFromAsset(String assetName) {
            mBlockDefAssets.add(assetName);
            return this;
        }

        /**
         * Add sets of block definitions to load from multiple JSON asset file. These will be added
         * to the set of all known blocks, but will not appear in the user's toolbox unless they are
         * also defined in the toolbox configuration via {@link #setToolboxConfigurationResId(int)}.
         * <p/>
         * The asset names must be a path to files in the assets directory. If the files contain
         * blocks that were previously defined, they will be overridden. A duplicate block is any
         * block with the same {@link Block#getName() name}.
         *
         * @param assetNames The paths of the assets to load.
         * @return this
         */
        public Builder addBlockDefinitionsFromAssets(List<String> assetNames) {
            for (String assetName : assetNames) {
                mBlockDefAssets.add(assetName);
            }
            return this;
        }
        /**
         * Adds a list of blocks to the set of all known blocks. These will be added to the set of
         * all known blocks, but will not appear in the user's toolbox unless they are also defined
         * in the toolbox configuration via {@link #setToolboxConfigurationResId(int)}.
         * <p/>
         * These blocks may not have any child blocks attached to them. If these blocks are
         * duplicates of blocks loaded from a resource they will override the block from resources.
         * Blocks added here will always be loaded after any blocks added with {@link
         * #addBlockDefinitions(int)};
         * <p/>
         * A duplicate block is any block with the same {@link Block#getName() name}.
         *
         * @param blocks The list of blocks to add to the workspace.
         * @return this
         */
        public Builder addBlockDefinitions(List<Block> blocks) {
            mBlockDefs.addAll(blocks);
            return this;
        }

        /**
         * Sets the resource to load the toolbox configuration from. This must be an xml resource in
         * the raw directory.
         * <p/>
         * If this is set, {@link #setToolboxConfiguration(String)} and {@link
         * #setToolboxConfigurationAsset(String)} may not be set.
         *
         * @param toolboxResId The resource id for the toolbox config file.
         * @return this
         */
        public Builder setToolboxConfigurationResId(int toolboxResId) {
            if (mToolboxXml != null && mToolboxAssetId != null) {
                throw new IllegalStateException("Toolbox res id may not be set if xml is set.");
            }
            mToolboxResId = toolboxResId;
            return this;
        }

        /**
         * Sets the asset to load the toolbox configuration from. The asset name must be a path to a
         * file in the assets directory.
         * <p/>
         * If this is set, {@link #setToolboxConfiguration(String)} and {@link
         * #setToolboxConfigurationResId(int)} may not be set.
         *
         * @param assetName The asset for the toolbox config file.
         * @return this
         */
        public Builder setToolboxConfigurationAsset(String assetName) {
            if (mToolboxXml != null && mToolboxResId != 0) {
                throw new IllegalStateException("Toolbox res id may not be set if xml is set.");
            }
            mToolboxAssetId = assetName;
            return this;
        }

        /**
         * Sets the XML to use for toolbox configuration.
         * <p/>
         * If this is set, {@link #setToolboxConfigurationResId(int)} and {@link
         * #setToolboxConfigurationAsset(String)} may not be set.
         *
         * @param toolboxXml The XML for configuring the toolbox.
         * @return this
         */
        public Builder setToolboxConfiguration(String toolboxXml) {
            if (mToolboxResId != 0 && mToolboxAssetId != null) {
                throw new IllegalStateException("Toolbox xml may not be set if a res id is set");
            }
            mToolboxXml = toolboxXml;
            return this;
        }

        /**
         * Create a new workspace using the configuration in this builder.
         *
         * @return A new {@link BlocklyController}.
         */
        public BlocklyController build() {
            if (mFragmentManager == null && (mWorkspaceFragment != null || mTrashFragment != null
                    || mToolboxFragment != null || mToolboxDrawer != null)) {
                throw new IllegalStateException(
                        "FragmentManager cannot be null when using Fragments.");
            }

            BlockFactory factory = new BlockFactory(mContext, null);
            for (int i = 0; i < mBlockDefResources.size(); i++) {
                factory.addBlocks(mBlockDefResources.get(i));
            }
            for (int i = 0; i < mBlockDefAssets.size(); i++) {
                try {
                    Log.d(TAG, "mBlockDefAssets #" + i + ": " + mBlockDefAssets.get(i));
                    factory.addBlocks(mAssetManager.open(mBlockDefAssets.get(i)));
                } catch (IOException e) {
                    throw new IllegalArgumentException("Failed to load block definitions "
                            + mBlockDefAssets.get(i), e);
                }
            }
            for (int i = 0; i < mBlockDefs.size(); i++) {
                factory.addBlockTemplate(mBlockDefs.get(i));
            }
            BlocklyController controller =
                    new BlocklyController(mContext, factory, mFragmentManager, mStyle);
            if (mToolboxResId != 0) {
                controller.loadToolboxContents(mToolboxResId);
            } else if (mToolboxXml != null) {
                controller.loadToolboxContents(mToolboxXml);
            } else if (mToolboxAssetId != null && mAssetManager != null) {
                try {
                    controller.loadToolboxContents(mAssetManager.open(mToolboxAssetId));
                } catch (IOException e) {
                    throw new IllegalArgumentException("Failed to load toolbox from assets "
                            + mToolboxAssetId, e);
                }
            }

            // Any of the following may be null and result in a no-op.
            controller.setWorkspaceFragment(mWorkspaceFragment);
            controller.setTrashFragment(mTrashFragment);
            controller.setToolboxFragment(mToolboxFragment);

            return controller;
        }
    }
}
