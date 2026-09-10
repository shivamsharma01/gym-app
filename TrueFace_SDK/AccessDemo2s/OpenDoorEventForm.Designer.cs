namespace AccessDemo2s
{
    partial class OpenDoorEventForm
    {
        /// <summary>
        /// Required designer variable.
        /// </summary>
        private System.ComponentModel.IContainer components = null;

        /// <summary>
        /// Clean up any resources being used.
        /// </summary>
        /// <param name="disposing">true if managed resources should be disposed; otherwise, false.</param>
        protected override void Dispose(bool disposing)
        {
            if (disposing && (components != null))
            {
                components.Dispose();
            }
            base.Dispose(disposing);
        }

        #region Windows Form Designer generated code

        /// <summary>
        /// Required method for Designer support - do not modify
        /// the contents of this method with the code editor.
        /// </summary>
        private void InitializeComponent()
        {
            this.listView_realLoadEvent = new System.Windows.Forms.ListView();
            this.btn_RealLoad = new System.Windows.Forms.Button();
            this.channel_comboBox = new System.Windows.Forms.ComboBox();
            this.label8 = new System.Windows.Forms.Label();
            this.groupBox_globalimage = new System.Windows.Forms.GroupBox();
            this.pictureBox_image = new System.Windows.Forms.PictureBox();
            this.groupBox1 = new System.Windows.Forms.GroupBox();
            this.pictureBox_faceimage = new System.Windows.Forms.PictureBox();
            this.groupBox2 = new System.Windows.Forms.GroupBox();
            this.pictureBox_candidateimage = new System.Windows.Forms.PictureBox();
            this.button1 = new System.Windows.Forms.Button();
            this.groupBox_globalimage.SuspendLayout();
            ((System.ComponentModel.ISupportInitialize)(this.pictureBox_image)).BeginInit();
            this.groupBox1.SuspendLayout();
            ((System.ComponentModel.ISupportInitialize)(this.pictureBox_faceimage)).BeginInit();
            this.groupBox2.SuspendLayout();
            ((System.ComponentModel.ISupportInitialize)(this.pictureBox_candidateimage)).BeginInit();
            this.SuspendLayout();
            // 
            // listView_realLoadEvent
            // 
            this.listView_realLoadEvent.Activation = System.Windows.Forms.ItemActivation.OneClick;
            this.listView_realLoadEvent.FullRowSelect = true;
            this.listView_realLoadEvent.GridLines = true;
            this.listView_realLoadEvent.HideSelection = false;
            this.listView_realLoadEvent.LabelEdit = true;
            this.listView_realLoadEvent.Location = new System.Drawing.Point(12, 367);
            this.listView_realLoadEvent.Name = "listView_realLoadEvent";
            this.listView_realLoadEvent.Size = new System.Drawing.Size(723, 229);
            this.listView_realLoadEvent.TabIndex = 5;
            this.listView_realLoadEvent.UseCompatibleStateImageBehavior = false;
            this.listView_realLoadEvent.View = System.Windows.Forms.View.Details;
            // 
            // btn_RealLoad
            // 
            this.btn_RealLoad.Font = new System.Drawing.Font("SimSun", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.btn_RealLoad.Location = new System.Drawing.Point(235, 8);
            this.btn_RealLoad.Margin = new System.Windows.Forms.Padding(4);
            this.btn_RealLoad.Name = "btn_RealLoad";
            this.btn_RealLoad.Size = new System.Drawing.Size(206, 24);
            this.btn_RealLoad.TabIndex = 17;
            this.btn_RealLoad.Text = "RealLoadEvent()";
            this.btn_RealLoad.UseVisualStyleBackColor = true;
            this.btn_RealLoad.Click += new System.EventHandler(this.btn_RealLoad_Click);
            // 
            // channel_comboBox
            // 
            this.channel_comboBox.DropDownStyle = System.Windows.Forms.ComboBoxStyle.DropDownList;
            this.channel_comboBox.FormattingEnabled = true;
            this.channel_comboBox.Location = new System.Drawing.Point(128, 10);
            this.channel_comboBox.Name = "channel_comboBox";
            this.channel_comboBox.Size = new System.Drawing.Size(71, 22);
            this.channel_comboBox.TabIndex = 19;
            // 
            // label8
            // 
            this.label8.AutoSize = true;
            this.label8.Location = new System.Drawing.Point(16, 13);
            this.label8.Margin = new System.Windows.Forms.Padding(4, 0, 4, 0);
            this.label8.Name = "label8";
            this.label8.Size = new System.Drawing.Size(105, 14);
            this.label8.TabIndex = 18;
            this.label8.Text = "Channel():";
            // 
            // groupBox_globalimage
            // 
            this.groupBox_globalimage.Controls.Add(this.pictureBox_image);
            this.groupBox_globalimage.Location = new System.Drawing.Point(12, 43);
            this.groupBox_globalimage.Name = "groupBox_globalimage";
            this.groupBox_globalimage.Size = new System.Drawing.Size(237, 318);
            this.groupBox_globalimage.TabIndex = 20;
            this.groupBox_globalimage.TabStop = false;
            this.groupBox_globalimage.Text = "Global Scene Image()";
            // 
            // pictureBox_image
            // 
            this.pictureBox_image.BackColor = System.Drawing.SystemColors.ActiveBorder;
            this.pictureBox_image.Location = new System.Drawing.Point(6, 20);
            this.pictureBox_image.Name = "pictureBox_image";
            this.pictureBox_image.Size = new System.Drawing.Size(225, 292);
            this.pictureBox_image.SizeMode = System.Windows.Forms.PictureBoxSizeMode.StretchImage;
            this.pictureBox_image.TabIndex = 0;
            this.pictureBox_image.TabStop = false;
            // 
            // groupBox1
            // 
            this.groupBox1.Controls.Add(this.pictureBox_faceimage);
            this.groupBox1.Location = new System.Drawing.Point(255, 43);
            this.groupBox1.Name = "groupBox1";
            this.groupBox1.Size = new System.Drawing.Size(237, 318);
            this.groupBox1.TabIndex = 21;
            this.groupBox1.TabStop = false;
            this.groupBox1.Text = "Face Image()";
            // 
            // pictureBox_faceimage
            // 
            this.pictureBox_faceimage.BackColor = System.Drawing.SystemColors.ActiveBorder;
            this.pictureBox_faceimage.Location = new System.Drawing.Point(6, 20);
            this.pictureBox_faceimage.Name = "pictureBox_faceimage";
            this.pictureBox_faceimage.Size = new System.Drawing.Size(225, 292);
            this.pictureBox_faceimage.SizeMode = System.Windows.Forms.PictureBoxSizeMode.StretchImage;
            this.pictureBox_faceimage.TabIndex = 0;
            this.pictureBox_faceimage.TabStop = false;
            // 
            // groupBox2
            // 
            this.groupBox2.Controls.Add(this.pictureBox_candidateimage);
            this.groupBox2.Location = new System.Drawing.Point(498, 43);
            this.groupBox2.Name = "groupBox2";
            this.groupBox2.Size = new System.Drawing.Size(237, 318);
            this.groupBox2.TabIndex = 22;
            this.groupBox2.TabStop = false;
            this.groupBox2.Text = "Candidate Image()";
            // 
            // pictureBox_candidateimage
            // 
            this.pictureBox_candidateimage.BackColor = System.Drawing.SystemColors.ActiveBorder;
            this.pictureBox_candidateimage.Location = new System.Drawing.Point(6, 20);
            this.pictureBox_candidateimage.Name = "pictureBox_candidateimage";
            this.pictureBox_candidateimage.Size = new System.Drawing.Size(225, 292);
            this.pictureBox_candidateimage.SizeMode = System.Windows.Forms.PictureBoxSizeMode.StretchImage;
            this.pictureBox_candidateimage.TabIndex = 0;
            this.pictureBox_candidateimage.TabStop = false;
            // 
            // button1
            // 
            this.button1.Font = new System.Drawing.Font("SimSun", 10.5F, System.Drawing.FontStyle.Regular, System.Drawing.GraphicsUnit.Point, ((byte)(134)));
            this.button1.Location = new System.Drawing.Point(461, 8);
            this.button1.Margin = new System.Windows.Forms.Padding(4);
            this.button1.Name = "button1";
            this.button1.Size = new System.Drawing.Size(206, 24);
            this.button1.TabIndex = 23;
            this.button1.Text = "Capture";
            this.button1.UseVisualStyleBackColor = true;
            this.button1.Click += new System.EventHandler(this.button1_Click);
            // 
            // OpenDoorEventForm
            // 
            this.AutoScaleDimensions = new System.Drawing.SizeF(7F, 14F);
            this.AutoScaleMode = System.Windows.Forms.AutoScaleMode.Font;
            this.ClientSize = new System.Drawing.Size(743, 608);
            this.Controls.Add(this.button1);
            this.Controls.Add(this.groupBox2);
            this.Controls.Add(this.groupBox1);
            this.Controls.Add(this.groupBox_globalimage);
            this.Controls.Add(this.channel_comboBox);
            this.Controls.Add(this.label8);
            this.Controls.Add(this.btn_RealLoad);
            this.Controls.Add(this.listView_realLoadEvent);
            this.Font = new System.Drawing.Font("SimSun", 10.5F);
            this.Name = "OpenDoorEventForm";
            this.StartPosition = System.Windows.Forms.FormStartPosition.CenterParent;
            this.Text = "OpenDoorEvent()";
            this.FormClosing += new System.Windows.Forms.FormClosingEventHandler(this.OpenDoorEventForm_FormClosing);
            this.Load += new System.EventHandler(this.OpenDoorEventForm_Load);
            this.groupBox_globalimage.ResumeLayout(false);
            ((System.ComponentModel.ISupportInitialize)(this.pictureBox_image)).EndInit();
            this.groupBox1.ResumeLayout(false);
            ((System.ComponentModel.ISupportInitialize)(this.pictureBox_faceimage)).EndInit();
            this.groupBox2.ResumeLayout(false);
            ((System.ComponentModel.ISupportInitialize)(this.pictureBox_candidateimage)).EndInit();
            this.ResumeLayout(false);
            this.PerformLayout();

        }

        #endregion
        private System.Windows.Forms.ListView listView_realLoadEvent;
        private System.Windows.Forms.Button btn_RealLoad;
        private System.Windows.Forms.ComboBox channel_comboBox;
        private System.Windows.Forms.Label label8;
        private System.Windows.Forms.GroupBox groupBox_globalimage;
        private System.Windows.Forms.PictureBox pictureBox_image;
        private System.Windows.Forms.GroupBox groupBox1;
        private System.Windows.Forms.PictureBox pictureBox_faceimage;
        private System.Windows.Forms.GroupBox groupBox2;
        private System.Windows.Forms.PictureBox pictureBox_candidateimage;
        private System.Windows.Forms.Button button1;
    }
}